package kr.jjw.graft.service

import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.TransportCloseReason
import kr.jjw.graft.files.TransferProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionRepositoryTest {
    @BeforeEach
    fun reset() {
        ConnectionRepository.reset()
    }

    @Test
    fun `starts idle`() {
        val state = ConnectionRepository.state.value
        assertEquals(ConnectionStatus.Idle, state.status)
        assertNull(state.peerName)
        assertTrue(state.transfers.isEmpty())
        assertNull(state.lastError)
    }

    @Test
    fun `connecting sets the status`() {
        ConnectionRepository.onEvent(ConnectionEvent.Connecting)
        assertEquals(ConnectionStatus.Connecting, ConnectionRepository.state.value.status)
    }

    @Test
    fun `a paired hello shows the peer name`() {
        ConnectionRepository.onPeerPaired("Galaxy S24")
        ConnectionRepository.onEvent(ConnectionEvent.Message(helloOk()))

        val state = ConnectionRepository.state.value
        assertEquals(ConnectionStatus.Connected("Galaxy S24"), state.status)
        assertEquals("Galaxy S24", state.peerName)
        assertNull(state.lastError)
    }

    @Test
    fun `pin mismatch stops retrying and asks for re-pairing`() {
        ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.PIN_MISMATCH))

        val state = ConnectionRepository.state.value
        assertEquals("Re-pair required", state.lastError)
        assertEquals(ConnectionStatus.Failed("Re-pair required"), state.status)
    }

    @Test
    fun `network failure shows a retry message`() {
        ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))

        val state = ConnectionRepository.state.value
        assertNotNull(state.lastError)
        assertEquals(ConnectionStatus.Connecting, state.status)
    }

    @Test
    fun `normal close returns to idle`() {
        ConnectionRepository.onPeerPaired("Phone")
        ConnectionRepository.onEvent(ConnectionEvent.Message(helloOk()))
        ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.NORMAL))

        assertEquals(ConnectionStatus.Idle, ConnectionRepository.state.value.status)
        assertNull(ConnectionRepository.state.value.peerName)
    }

    @Test
    fun `stop clears the peer and the transfers`() {
        ConnectionRepository.onPeerPaired("Phone")
        ConnectionRepository.onEvent(ConnectionEvent.Message(helloOk()))
        ConnectionRepository.onTransferProgress(mapOf(1u to TransferProgress("a.bin", 10, 100, kr.jjw.graft.core.TransferDirection.receive)))

        ConnectionRepository.onStopped()

        val state = ConnectionRepository.state.value
        assertEquals(ConnectionStatus.Idle, state.status)
        assertNull(state.peerName)
        assertTrue(state.transfers.isEmpty())
    }

    @Test
    fun `binary events do not change the status`() {
        ConnectionRepository.onEvent(ConnectionEvent.Message(helloOk()))
        val before = ConnectionRepository.state.value

        ConnectionRepository.onEvent(ConnectionEvent.Binary(ByteArray(4)))

        assertEquals(before, ConnectionRepository.state.value)
    }

    private fun helloOk(): Envelope = Envelope.create(
        type = "hello.ok",
        payload = kotlinx.serialization.json.buildJsonObject { }
    )
}
