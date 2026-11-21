package kr.jjw.graft.service

import java.io.File
import kr.jjw.graft.core.TransferDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Attaching the history store happens twice now: once in the service and once in the UI, and the
 * UI repeats it whenever the Activity is recreated. Neither may drop or duplicate rows.
 */
class HistoryAttachTest {
    @TempDir
    lateinit var directory: File

    private lateinit var file: File

    @BeforeEach
    fun startClean() {
        file = File(directory, "transfer-history.json")
        ConnectionRepository.forgetHistory()
    }

    private fun finish(id: UInt, name: String) {
        ConnectionRepository.onTransferFinished(
            id = id,
            name = name,
            bytes = 10,
            direction = TransferDirection.receive,
            error = null,
            destination = null,
        )
    }

    @Test
    fun `attaching a store keeps rows recorded before the store was available`() {
        // The service can start after the UI already recorded something, and vice versa.
        finish(7u, "receipt.pdf")

        val store = TransferHistoryStore(file)
        store.save(
            listOf(
                TransferRecord(
                    id = 1u,
                    name = "older.txt",
                    bytes = 5,
                    direction = TransferDirection.receive,
                    outcome = TransferOutcome.Completed,
                    reason = "Completed",
                    destination = null,
                    finishedAtMillis = 1_000,
                )
            )
        )

        ConnectionRepository.attachHistoryStore(store)

        val ids = ConnectionRepository.state.value.history.map { it.id }
        assertEquals(setOf(1u, 7u), ids.toSet())
        assertEquals(2, ids.size)
    }

    @Test
    fun `attaching twice neither duplicates nor truncates`() {
        val store = TransferHistoryStore(file)
        ConnectionRepository.attachHistoryStore(store)
        finish(1u, "first.bin")
        finish(2u, "second.bin")

        // The Activity was recreated: same file, separate store instance, same rows.
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))

        val history = ConnectionRepository.state.value.history
        assertEquals(listOf(2u, 1u), history.map { it.id })
    }

    @Test
    fun `history keeps the newest fifty rows`() {
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))

        for (id in 1u..60u) finish(id, "file-$id")

        val history = ConnectionRepository.state.value.history
        assertEquals(TRANSFER_HISTORY_LIMIT, history.size)
        assertEquals(60u, history.first().id)
        assertTrue(history.none { it.id < 11u })
    }
}
