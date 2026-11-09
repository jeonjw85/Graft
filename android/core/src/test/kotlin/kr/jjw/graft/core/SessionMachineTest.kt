package kr.jjw.graft.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionMachineTest {
    private data class Row(
        val from: SessionPhase,
        val event: SessionEvent,
        val to: SessionPhase,
        val actions: List<SessionAction>,
    )

    private fun loadFixture(): Pair<List<Row>, List<SessionPhase>> {
        val file = File(
            System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures",
            "session/transitions.json"
        )
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val rows = root.getValue("transitions").jsonArray.map { element ->
            val row = element.jsonObject
            Row(
                from = SessionPhase.valueOf(row.getValue("from").jsonPrimitive.content),
                event = SessionEvent.parse(row.getValue("event").jsonPrimitive.content),
                to = SessionPhase.valueOf(row.getValue("to").jsonPrimitive.content),
                actions = row.getValue("actions").jsonArray.map {
                    SessionAction.valueOf(it.jsonPrimitive.content)
                },
            )
        }

        val mismatch = root.getValue("pinMismatch").jsonObject
        val phases = mismatch.getValue("from").jsonArray.map {
            SessionPhase.valueOf(it.jsonPrimitive.content)
        }
        return rows to phases
    }

    @Test
    fun `every transition row`() {
        val (rows, _) = loadFixture()
        assertTrue(rows.isNotEmpty())

        for (row in rows) {
            val machine = SessionMachine(row.from)
            val actions = machine.handle(row.event)

            assertEquals(row.to, machine.phase, "${row.from} --${row.event.fixtureName}--> ${row.to}")
            assertEquals(row.actions, actions, "${row.from} --${row.event.fixtureName}--> actions")
        }
    }

    @Test
    fun `pin mismatch never schedules reconnect`() {
        val (_, phases) = loadFixture()

        for (phase in phases) {
            val machine = SessionMachine(phase)
            val actions = machine.handle(SessionEvent.TlsFailed(CloseReason.pinMismatch))

            assertEquals(SessionPhase.closed, machine.phase)
            assertEquals(listOf(SessionAction.stopReconnect), actions)
            assertFalse(SessionAction.scheduleReconnect in actions)
        }
    }

    @Test
    fun `reconnect backoff sequence`() {
        val backoff = ReconnectBackoff { 0.0 }

        assertEquals(
            listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0, 60.0),
            (0 until 8).map { backoff.nextDelay() }
        )
    }

    @Test
    fun `backoff jitter stays within twenty percent`() {
        val backoff = ReconnectBackoff { upper -> upper }

        for (base in listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0)) {
            val delay = backoff.nextDelay()
            assertTrue(delay >= base)
            assertTrue(delay <= base * 1.2)
        }
    }

    @Test
    fun `backoff resets after success`() {
        val backoff = ReconnectBackoff { 0.0 }
        backoff.nextDelay()
        backoff.nextDelay()
        assertEquals(4.0, backoff.nextDelay())

        backoff.reset()
        assertEquals(1.0, backoff.nextDelay())
    }

    @Test
    fun `heartbeat emits ping every twenty seconds`() {
        val heartbeat = Heartbeat(0)

        assertFalse(heartbeat.shouldPing(19_000))
        assertTrue(heartbeat.shouldPing(20_000))
        assertFalse(heartbeat.shouldPing(39_000))
        assertTrue(heartbeat.shouldPing(40_000))
    }

    @Test
    fun `heartbeat timeout`() {
        val heartbeat = Heartbeat(0)

        assertFalse(heartbeat.isDead(19_000))
        assertFalse(heartbeat.isDead(59_000))
        assertTrue(heartbeat.isDead(60_000))

        heartbeat.noteActivity(50_000)
        assertFalse(heartbeat.isDead(100_000))
        assertTrue(heartbeat.isDead(110_000))
    }

    @Test
    fun `unpaired connection rejects a non pair message`() {
        val machine = SessionMachine(SessionPhase.unpaired)

        val actions = machine.handle(SessionEvent.MessageReceived)

        assertEquals(SessionPhase.closing, machine.phase)
        assertEquals(
            listOf(SessionAction.sendErrorUnpaired, SessionAction.closeConnection),
            actions
        )
    }
}
