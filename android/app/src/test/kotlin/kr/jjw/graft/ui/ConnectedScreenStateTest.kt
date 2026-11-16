package kr.jjw.graft.ui

import kr.jjw.graft.service.ConnectionStatus
import kr.jjw.graft.service.ConnectionUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectedScreenStateTest {
    private fun state(
        status: ConnectionStatus,
        clipboard: String? = null,
    ) = ConnectionUiState(status = status, peerName = "Galaxy S24", lastClipboard = clipboard)

    @Test
    fun `clipboard send is disabled while disconnected`() {
        val derived = ConnectedScreenState.from(state(ConnectionStatus.Idle))
        assertFalse(derived.canSendClipboard)
        assertNull(derived.clipboardNote)
        assertEquals("Not connected", derived.statusLabel)
    }

    @Test
    fun `clipboard send is enabled when connected`() {
        val derived = ConnectedScreenState.from(state(ConnectionStatus.Connected("Galaxy S24")))
        assertTrue(derived.canSendClipboard)
        assertEquals("Connected to Galaxy S24", derived.statusLabel)
        assertNotNull(derived.clipboardNote)
    }

    @Test
    fun `received text is previewed`() {
        val derived = ConnectedScreenState.from(
            state(ConnectionStatus.Connected("Galaxy S24"), clipboard = "안녕하세요 👋🏽")
        )
        assertEquals("안녕하세요 👋🏽", derived.lastReceived)
    }

    @Test
    fun `failure message is shown verbatim`() {
        val derived = ConnectedScreenState.from(state(ConnectionStatus.Failed("Re-pair required")))
        assertEquals("Re-pair required", derived.statusLabel)
        assertFalse(derived.canSendClipboard)
    }

    @Test
    fun `connecting shows the retry note`() {
        val derived = ConnectedScreenState.from(
            ConnectionUiState(status = ConnectionStatus.Connecting, lastError = "Connection lost, retrying")
        )
        assertEquals("Connection lost, retrying", derived.statusLabel)
    }
}
