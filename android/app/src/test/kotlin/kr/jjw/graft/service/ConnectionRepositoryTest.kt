package kr.jjw.graft.service

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionRepositoryTest {
    @BeforeEach
    fun reset() {
        ConnectionRepository.reset()
    }

    @Test
    fun `progress is published as a snapshot, not a live map`() {
        val live = mutableMapOf(
            1u to TransferProgress("a.bin", 0, 10, TransferDirection.receive)
        )
        ConnectionRepository.onTransferProgress(live)

        live[1u] = live.getValue(1u).copy(confirmed = 5)

        assertEquals(0, ConnectionRepository.state.value.transfers.getValue(1u).confirmed)
    }

    @Test
    fun `sending the clipboard does not overwrite what was received`() {
        ConnectionRepository.onClipboardReceived("from mac")

        ConnectionRepository.onSubmitClipboard("from phone")

        assertEquals("from mac", ConnectionRepository.state.value.lastClipboard)
        assertEquals("from phone", ConnectionRepository.state.value.lastSentClipboard)
    }

    @Test
    fun `a reset forgets both clipboard sides`() {
        ConnectionRepository.onClipboardReceived("from mac")
        ConnectionRepository.onSubmitClipboard("from phone")

        ConnectionRepository.reset()

        assertNull(ConnectionRepository.state.value.lastClipboard)
        assertNull(ConnectionRepository.state.value.lastSentClipboard)
    }
}
