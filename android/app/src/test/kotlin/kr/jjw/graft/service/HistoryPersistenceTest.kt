package kr.jjw.graft.service

import java.io.File
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferFailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The whole path a finished transfer takes: repository → JSON file → fresh repository.
 *
 * The device check cannot prove this because every reconnect burns the single-use pairing token;
 * this test exercises the exact code the service runs.
 */
class HistoryPersistenceTest {
    private val file = File.createTempFile("graft-persist", ".json").also { it.delete() }

    @AfterEach
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun `a finished transfer is still listed after the service restarts`() {
        ConnectionRepository.reset()
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        ConnectionRepository.onTransferFinished(
            id = 1u,
            name = "design-spec.jpg",
            bytes = 45_600,
            direction = TransferDirection.receive,
            error = null,
            destination = "content://media/external/downloads/12",
        )
        assertEquals(1, ConnectionRepository.state.value.history.size)

        // A new process: fresh repository, same file.
        ConnectionRepository.reset()
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        val restored = ConnectionRepository.state.value.history

        assertEquals(1, restored.size)
        assertEquals("design-spec.jpg", restored.first().name)
        assertEquals(45_600, restored.first().bytes)
        assertEquals("content://media/external/downloads/12", restored.first().destination)
        assertEquals(TransferOutcome.Completed, restored.first().outcome)
    }

    @Test
    fun `a failed transfer keeps its reason across a restart`() {
        ConnectionRepository.reset()
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        ConnectionRepository.onTransferFinished(
            id = 2u,
            name = "broken.bin",
            bytes = 10,
            direction = TransferDirection.receive,
            error = TransferFailureKind.HASH_MISMATCH,
            destination = null,
        )

        ConnectionRepository.reset()
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))

        val row = ConnectionRepository.state.value.history.single()
        assertEquals("Hash mismatch", row.reason)
        assertEquals(TransferOutcome.Failed, row.outcome)
    }
}
