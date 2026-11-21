package kr.jjw.graft.service

import java.io.File
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferFailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The whole path a finished transfer takes: repository → JSON file → fresh repository.
 *
 * The device check cannot prove this because every reconnect burns the single-use pairing token;
 * this test exercises the exact code the service runs.
 */
class HistoryPersistenceTest {
    private val file = File.createTempFile("graft-persist", ".json").also { it.delete() }

    @BeforeEach
    fun startClean() {
        // History now outlives a reset, so clear both the file and the memory between tests.
        ConnectionRepository.forgetHistory()
    }

    @Test
    fun `a finished transfer is still listed after the service restarts`() {
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        ConnectionRepository.onTransferFinished(
            id = 1u,
            name = "design-spec.jpg",
            bytes = 45_600,
            direction = TransferDirection.receive,
            error = null,
            destination = "content://media/external/downloads/12",
        )

        val onDisk = TransferHistoryStore(file).load()
        assertEquals(1, onDisk.size)
        assertEquals("design-spec.jpg", onDisk.first().name)
        assertEquals(45_600, onDisk.first().bytes)
        assertEquals("content://media/external/downloads/12", onDisk.first().destination)
        assertEquals(TransferOutcome.Completed, onDisk.first().outcome)

        // The service starting over resets the session, not the list the user is looking at.
        ConnectionRepository.reset()
        assertEquals(1, ConnectionRepository.state.value.history.size)
    }

    @Test
    fun `a failed transfer keeps its reason across a restart`() {
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        ConnectionRepository.onTransferFinished(
            id = 2u,
            name = "broken.bin",
            bytes = 10,
            direction = TransferDirection.receive,
            error = TransferFailureKind.HASH_MISMATCH,
            destination = null,
        )

        val row = TransferHistoryStore(file).load().single()
        assertEquals("Hash mismatch", row.reason)
        assertEquals(TransferOutcome.Failed, row.outcome)
    }

    @Test
    fun `disconnecting keeps the rows already shown`() {
        ConnectionRepository.attachHistoryStore(TransferHistoryStore(file))
        ConnectionRepository.onTransferFinished(
            id = 3u,
            name = "notes.txt",
            bytes = 12,
            direction = TransferDirection.send,
            error = null,
            destination = null,
        )

        ConnectionRepository.onStopped()

        val row = ConnectionRepository.state.value.history.single()
        assertEquals("notes.txt", row.name)
        assertEquals(TransferDirection.send, row.direction)
    }
}
