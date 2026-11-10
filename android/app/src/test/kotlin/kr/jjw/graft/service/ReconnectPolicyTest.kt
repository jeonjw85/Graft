package kr.jjw.graft.service

import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.TransportCloseReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {
    @Test
    fun `pin mismatch stops retrying`() {
        val policy = ReconnectPolicy()

        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.PIN_MISMATCH))

        assertEquals(ConnectionStatus.Failed("Re-pair required"), policy.status)
        assertTrue(policy.pendingDelays().isEmpty())
    }

    @Test
    fun `network failure retries with backoff`() {
        val policy = ReconnectPolicy()

        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))
        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))
        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))

        assertEquals(listOf(1L, 2L, 4L), policy.pendingDelays())
    }

    @Test
    fun `successful connection resets backoff`() {
        val policy = ReconnectPolicy()
        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))
        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))

        policy.onEvent(ConnectionEvent.Message(Envelope.create("hello.ok")))

        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))
        assertEquals(listOf(1L), policy.pendingDelays().takeLast(1))
    }

    @Test
    fun `normal close does not schedule a retry`() {
        val policy = ReconnectPolicy()

        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NORMAL))

        assertEquals(ConnectionStatus.Idle, policy.status)
        assertTrue(policy.pendingDelays().isEmpty())
    }

    @Test
    fun `message updates status to connected`() {
        val policy = ReconnectPolicy()

        policy.onEvent(ConnectionEvent.Message(Envelope.create("hello.ok")))

        assertEquals(ConnectionStatus.Connected("hello.ok"), policy.status)
    }

    @Test
    fun `pin mismatch after retries clears the queue`() {
        val policy = ReconnectPolicy()
        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))

        policy.onEvent(ConnectionEvent.Closed(TransportCloseReason.PIN_MISMATCH))

        assertTrue(policy.pendingDelays().isEmpty())
    }
}
