package kr.jjw.graft.service

import java.io.File
import kr.jjw.graft.core.TransferDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransferHistoryStoreTest {
    private val file = File.createTempFile("graft-history", ".json").also { it.delete() }
    private val store = TransferHistoryStore(file)

    private fun record(
        id: UInt = 1u,
        name: String = "photo.jpg",
        destination: String? = "content://downloads/1",
        error: kr.jjw.graft.files.TransferFailureKind? = null,
    ) = TransferRecord(
        id = id,
        name = name,
        bytes = 1024,
        direction = TransferDirection.receive,
        outcome = if (error == null) TransferOutcome.Completed else TransferOutcome.Failed,
        reason = TransferHistory.messageFor(error),
        destination = destination,
        finishedAtMillis = 1_762_752_000_000,
    )

    @Test
    fun `records survive a restart`() {
        store.save(listOf(record(name = "a.bin"), record(id = 2u, name = "b.bin")))

        val reloaded = TransferHistoryStore(file).load()

        assertEquals(listOf("a.bin", "b.bin"), reloaded.map { it.name })
    }

    @Test
    fun `a missing file loads as empty`() {
        assertTrue(TransferHistoryStore(File(file.path + ".missing")).load().isEmpty())
    }

    @Test
    fun `a corrupt file loads as empty instead of crashing`() {
        file.writeText("{not json")

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `only the newest rows the limit allows are kept`() {
        // TransferHistory keeps newest first, so the list handed to the store is already ordered.
        val many = (60 downTo 1).map { record(id = it.toUInt(), name = "f$it") }

        store.save(many)

        val reloaded = store.load()
        assertEquals(TRANSFER_HISTORY_LIMIT, reloaded.size)
        assertEquals("f60", reloaded.first().name)
    }

    @Test
    fun `the destination and reason survive`() {
        store.save(listOf(record(destination = "/data/a.txt", error = kr.jjw.graft.files.TransferFailureKind.HASH_MISMATCH)))

        val row = TransferHistoryStore(file).load().single()

        assertEquals("/data/a.txt", row.destination)
        assertEquals("Hash mismatch", row.reason)
        assertEquals(TransferOutcome.Failed, row.outcome)
    }
}
