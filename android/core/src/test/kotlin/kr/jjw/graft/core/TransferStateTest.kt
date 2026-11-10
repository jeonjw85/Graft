package kr.jjw.graft.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransferStateTest {
    private val start = 1_762_752_000_000L

    private fun record(offset: ULong = 0uL) = TransferRecord(
        transferId = 7u,
        phase = TransferPhase.idle,
        name = "보고서.pdf",
        size = 200_000uL,
        bytesConfirmed = offset,
        sha256 = null,
        direction = TransferDirection.receive,
        startedAt = start,
    )

    // MARK: - Fixture rows

    @Test
    fun `every transition row`() {
        val rows = loadRows()
        assertEquals(12, rows.size)

        for (row in rows) {
            val machine = TransferMachine(record())
            machine.forcePhase(row.from)

            val event = row.makeEvent(machine.record.bytesConfirmed)
            val actions = machine.handle(event)

            assertEquals(row.to, machine.phase, "${row.from} --${row.event}--> ${row.to}")
            assertEquals(row.actions, describe(actions), "${row.from} --${row.event}--> actions")
        }
    }

    // MARK: - Offset rules

    @Test
    fun `chunk at the wrong offset fails`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)
        machine.handle(TransferEvent.Accept(0uL))

        val actions = machine.handle(TransferEvent.Chunk(64uL * 1024uL, 64))

        assertEquals(TransferPhase.failed, machine.phase)
        assertEquals(listOf("abort"), describe(actions))
        assertEquals(0uL, machine.record.bytesConfirmed)
    }

    @Test
    fun `chunks advance the confirmed offset`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)
        machine.handle(TransferEvent.Accept(0uL))

        machine.handle(TransferEvent.Chunk(0uL, 64 * 1024))
        assertEquals(64uL * 1024uL, machine.record.bytesConfirmed)

        machine.handle(TransferEvent.Chunk(64uL * 1024uL, 64 * 1024))
        assertEquals(128uL * 1024uL, machine.record.bytesConfirmed)
    }

    @Test
    fun `resume seeks to the accepted offset`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)

        val actions = machine.handle(TransferEvent.Accept(1024uL))

        assertEquals(TransferPhase.receiving, machine.phase)
        assertEquals(1024uL, machine.record.bytesConfirmed)
        assertEquals(listOf("seekTo(1024)"), describe(actions))
    }

    @Test
    fun `resume rejects a seek backwards`() {
        val machine = TransferMachine(record(4096uL))
        machine.handle(TransferEvent.Offer)

        machine.handle(TransferEvent.Accept(1024uL))

        assertEquals(TransferPhase.failed, machine.phase)
    }

    @Test
    fun `accept beyond the file size fails`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)

        machine.handle(TransferEvent.Accept(300_000uL))

        assertEquals(TransferPhase.failed, machine.phase)
    }

    @Test
    fun `chunk after completion is ignored`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)
        machine.handle(TransferEvent.Accept(0uL))
        machine.handle(TransferEvent.Complete)
        machine.handle(TransferEvent.HashOk)
        assertEquals(TransferPhase.completed, machine.phase)

        val actions = machine.handle(TransferEvent.Chunk(0uL, 10))

        assertEquals(TransferPhase.completed, machine.phase)
        assertTrue(actions.isEmpty())
    }

    // MARK: - Stall

    @Test
    fun `thirty seconds of silence fails the transfer`() {
        val machine = TransferMachine(record())
        machine.handle(TransferEvent.Offer)
        machine.handle(TransferEvent.Accept(0uL))

        machine.tick(start + 29_000)
        assertEquals(TransferPhase.receiving, machine.phase)

        val actions = machine.tick(start + 30_000)
        assertEquals(TransferPhase.failed, machine.phase)
        assertEquals(listOf("abort"), describe(actions))
    }

    // MARK: - Helpers

    private fun describe(actions: List<TransferAction>): List<String> = actions.map { action ->
        when (action) {
            is TransferAction.SeekTo -> "seekTo(${action.offset})"
            TransferAction.Abort -> "abort"
            TransferAction.ComputeHash -> "computeHash"
            TransferAction.CommitFile -> "commitFile"
            TransferAction.DeletePartial -> "deletePartial"
        }
    }

    private data class Row(
        val from: TransferPhase,
        val event: String,
        val offset: String?,
        val length: Int?,
        val to: TransferPhase,
        val actions: List<String>,
    ) {
        fun makeEvent(bytesConfirmed: ULong): TransferEvent = when (event) {
            "offer" -> TransferEvent.Offer
            "accept" -> TransferEvent.Accept((offset ?: "0").toULong())
            "reject" -> TransferEvent.Reject
            "chunk" -> {
                val value = when (offset) {
                    "confirmed" -> bytesConfirmed
                    "mismatch" -> bytesConfirmed + 64uL * 1024uL + 1uL
                    else -> (offset ?: "0").toULong()
                }
                TransferEvent.Chunk(value, length ?: 0)
            }
            "complete" -> TransferEvent.Complete
            "hashOk" -> TransferEvent.HashOk
            "hashMismatch" -> TransferEvent.HashMismatch
            "stall" -> TransferEvent.Stall
            "cancel" -> TransferEvent.Cancel
            else -> error("unknown event $event")
        }
    }

    private fun loadRows(): List<Row> {
        val file = File(
            System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures",
            "transfer/transitions.json"
        )
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("transitions").jsonArray.map { element ->
            val row = element.jsonObject
            Row(
                from = TransferPhase.valueOf(row.getValue("from").jsonPrimitive.content),
                event = row.getValue("event").jsonPrimitive.content,
                offset = row["offset"]?.jsonPrimitive?.content,
                length = row["length"]?.jsonPrimitive?.content?.toIntOrNull(),
                to = TransferPhase.valueOf(row.getValue("to").jsonPrimitive.content),
                actions = row.getValue("actions").jsonArray.map { action ->
                    val primitive = action as? kotlinx.serialization.json.JsonPrimitive
                    if (primitive != null) {
                        primitive.content
                    } else {
                        val objectValue = action.jsonObject
                        "seekTo(${objectValue.getValue("seekTo").jsonPrimitive.content})"
                    }
                },
            )
        }
    }
}
