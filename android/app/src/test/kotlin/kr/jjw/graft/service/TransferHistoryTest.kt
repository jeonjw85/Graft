package kr.jjw.graft.service

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferFailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransferHistoryTest {
    private val history = TransferHistory(capacity = 3, clock = { 1_000L })

    private fun record(
        id: UInt = 1u,
        name: String = "a.txt",
        bytes: Long = 10,
        error: TransferFailureKind? = null,
        destination: String? = null,
        direction: TransferDirection = TransferDirection.receive,
    ) = history.record(
        id = id,
        name = name,
        bytes = bytes,
        direction = direction,
        error = error,
        destination = destination,
    )

    @Test
    fun `records are newest first`() {
        record(name = "a.txt")
        record(id = 2u, name = "b.txt")

        assertEquals(listOf("b.txt", "a.txt"), history.list.map { it.name })
    }

    @Test
    fun `the list is capped`() {
        repeat(4) { index -> record(id = index.toUInt() + 1u, name = "f$index") }

        assertEquals(3, history.list.size)
        assertEquals("f3", history.list.first().name)
        assertTrue(history.list.none { it.name == "f0" }, "the oldest row is dropped")
    }

    @Test
    fun `a completed transfer is marked completed`() {
        val record = record(error = null)

        assertEquals(TransferOutcome.Completed, record.outcome)
        assertEquals("Completed", record.reason)
    }

    @Test
    fun `a hash mismatch keeps its reason`() {
        val record = record(error = TransferFailureKind.HASH_MISMATCH)

        assertEquals(TransferOutcome.Failed, record.outcome)
        assertEquals("Hash mismatch", record.reason)
    }

    @Test
    fun `a rejection reads as rejected`() {
        assertEquals("Rejected", record(error = TransferFailureKind.REJECTED).reason)
    }

    @Test
    fun `a received transfer keeps its destination`() {
        val record = record(destination = "content://downloads/1")

        assertEquals("content://downloads/1", record.destination)
        assertTrue(record.canOpen)
    }

    @Test
    fun `a sent transfer has nothing to open`() {
        val record = record(direction = TransferDirection.send)

        assertEquals(null, record.destination)
        assertTrue(!record.canOpen)
    }

    @Test
    fun `a retried transfer replaces the earlier row`() {
        record(id = 7u, name = "first.txt", error = TransferFailureKind.WRONG_OFFSET)
        record(id = 7u, name = "second.txt")

        assertEquals(1, history.list.size, "the same transfer must not appear twice")
        assertEquals("second.txt", history.list.first().name)
    }
}
