package kr.jjw.graft.service

import kr.jjw.graft.core.CloseReason
import kr.jjw.graft.core.ReconnectBackoff
import kr.jjw.graft.core.SessionAction
import kr.jjw.graft.core.SessionMachine
import kr.jjw.graft.core.SessionPhase
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.TransportCloseReason

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class Connected(val peer: String) : ConnectionStatus
    data class Failed(val message: String) : ConnectionStatus
}

/**
 * Turns transport events into reconnect decisions.
 *
 * A pin mismatch is the one failure that must not be retried automatically: retrying would fight
 * the user, who can only recover by re-pairing. Everything else backs off exponentially.
 */
class ReconnectPolicy(
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
) {
    var status: ConnectionStatus = ConnectionStatus.Idle
        private set

    private val machine = SessionMachine()
    private val scheduled = mutableListOf<Long>()

    fun onEvent(event: ConnectionEvent) {
        when (event) {
            ConnectionEvent.Connecting -> {
                status = ConnectionStatus.Connecting
                machine.handle(kr.jjw.graft.core.SessionEvent.Connect)
            }

            is ConnectionEvent.Secure -> {
                machine.handle(kr.jjw.graft.core.SessionEvent.TlsReady(event.serverFingerprint))
            }

            is ConnectionEvent.Message -> {
                status = ConnectionStatus.Connected(event.envelope.type)
                machine.handle(kr.jjw.graft.core.SessionEvent.HelloReceived)
                backoff.reset()
            }

            is ConnectionEvent.Closed -> onClosed(event.reason)
        }
    }

    private fun onClosed(reason: TransportCloseReason) {
        when (reason) {
            TransportCloseReason.PIN_MISMATCH -> {
                machine.handle(kr.jjw.graft.core.SessionEvent.TlsFailed(CloseReason.pinMismatch))
                status = ConnectionStatus.Failed("Re-pair required")
                scheduled.clear()
            }

            TransportCloseReason.NORMAL -> {
                status = ConnectionStatus.Idle
                scheduled.clear()
            }

            TransportCloseReason.NETWORK, TransportCloseReason.UNKNOWN -> {
                machine.handle(kr.jjw.graft.core.SessionEvent.TlsFailed(CloseReason.fatal))
                val delay = backoff.nextDelay()
                scheduled.add(delay.toLong())
                status = ConnectionStatus.Failed("Reconnecting in ${delay.toInt()}s")
            }
        }
    }

    /** Delays scheduled for future reconnect attempts, oldest first. */
    fun pendingDelays(): List<Long> = scheduled.toList()

    fun phase(): SessionPhase = machine.phase
}
