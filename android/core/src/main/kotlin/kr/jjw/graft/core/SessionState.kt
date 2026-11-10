package kr.jjw.graft.core

enum class SessionPhase {
    disconnected, connecting, authenticating, unpaired, pairing, paired, ready, closing, closed
}

enum class CloseReason {
    pinMismatch, unknownDevice, tokenInvalid, tokenExpired, rateLimited, protoUnsupported, fatal
}

sealed interface SessionEvent {
    data object Connect : SessionEvent
    data class TlsReady(val peerFingerprint: String) : SessionEvent
    data class TlsFailed(val reason: CloseReason) : SessionEvent
    data object PairRequest : SessionEvent
    data object PairAccepted : SessionEvent
    data object PairRejected : SessionEvent
    data object HelloReceived : SessionEvent
    data object HelloAcked : SessionEvent
    data object MessageReceived : SessionEvent
    data object PingReceived : SessionEvent
    data object PingTimeout : SessionEvent
    data object Disconnect : SessionEvent
    data object Close : SessionEvent

    /** Name used in the shared transition fixture. */
    val fixtureName: String
        get() = when (this) {
            Connect -> "connect"
            is TlsReady -> "tlsReady"
            is TlsFailed -> "tlsFailed"
            PairRequest -> "pairRequest"
            PairAccepted -> "pairAccepted"
            PairRejected -> "pairRejected"
            HelloReceived -> "helloReceived"
            HelloAcked -> "helloAcked"
            MessageReceived -> "messageReceived"
            PingReceived -> "pingReceived"
            PingTimeout -> "pingTimeout"
            Disconnect -> "disconnect"
            Close -> "close"
        }

    companion object {
        fun parse(name: String): SessionEvent = when (name) {
            "connect" -> Connect
            "tlsReady" -> TlsReady("")
            "tlsFailed" -> TlsFailed(CloseReason.fatal)
            "pairRequest" -> PairRequest
            "pairAccepted" -> PairAccepted
            "pairRejected" -> PairRejected
            "helloReceived" -> HelloReceived
            "helloAcked" -> HelloAcked
            "messageReceived" -> MessageReceived
            "pingReceived" -> PingReceived
            "pingTimeout" -> PingTimeout
            "disconnect" -> Disconnect
            "close" -> Close
            else -> error("unknown event: $name")
        }
    }
}

enum class SessionAction {
    sendPairOk, sendHello, sendHelloOk, sendPairError, sendErrorUnpaired,
    scheduleReconnect, stopReconnect, startHeartbeat, stopHeartbeat, closeConnection,
}

/**
 * The connection state machine shared (by fixture) with the Swift implementation.
 *
 * Unknown `(phase, event)` pairs are ignored rather than throwing: a peer can send any frame.
 */
class SessionMachine(phase: SessionPhase = SessionPhase.disconnected) {
    var phase: SessionPhase = phase
        private set

    fun handle(event: SessionEvent): List<SessionAction> {
        when (phase) {
            SessionPhase.disconnected ->
                if (event is SessionEvent.Connect) {
                    phase = SessionPhase.connecting
                    return emptyList()
                }

            SessionPhase.connecting -> when (event) {
                is SessionEvent.TlsReady -> {
                    phase = SessionPhase.authenticating
                    return emptyList()
                }
                is SessionEvent.TlsFailed -> return onTlsFailed(event.reason)
                else -> Unit
            }

            SessionPhase.authenticating -> when (event) {
                is SessionEvent.TlsFailed -> return onTlsFailed(event.reason)
                is SessionEvent.PairRequest -> {
                    phase = SessionPhase.unpaired
                    return emptyList()
                }
                is SessionEvent.HelloReceived -> {
                    phase = SessionPhase.ready
                    return listOf(SessionAction.sendHelloOk, SessionAction.startHeartbeat)
                }
                is SessionEvent.MessageReceived, is SessionEvent.PingReceived -> {
                    // Before pairing, anything other than pair.request is refused and closed.
                    phase = SessionPhase.closing
                    return listOf(SessionAction.sendErrorUnpaired, SessionAction.closeConnection)
                }
                else -> Unit
            }

            SessionPhase.unpaired -> when (event) {
                is SessionEvent.PairRequest -> {
                    phase = SessionPhase.pairing
                    return emptyList()
                }
                is SessionEvent.MessageReceived -> {
                    phase = SessionPhase.closing
                    return listOf(SessionAction.sendErrorUnpaired, SessionAction.closeConnection)
                }
                else -> Unit
            }

            SessionPhase.pairing -> when (event) {
                is SessionEvent.PairAccepted -> {
                    phase = SessionPhase.paired
                    return listOf(SessionAction.sendPairOk, SessionAction.sendHello)
                }
                is SessionEvent.PairRejected -> {
                    phase = SessionPhase.closing
                    return listOf(SessionAction.closeConnection)
                }
                else -> Unit
            }

            SessionPhase.paired -> when (event) {
                is SessionEvent.HelloReceived -> {
                    phase = SessionPhase.ready
                    return listOf(SessionAction.sendHelloOk, SessionAction.startHeartbeat)
                }
                is SessionEvent.HelloAcked -> {
                    phase = SessionPhase.ready
                    return listOf(SessionAction.startHeartbeat)
                }
                else -> Unit
            }

            SessionPhase.ready -> when (event) {
                is SessionEvent.HelloReceived -> return listOf(SessionAction.sendHelloOk)
                is SessionEvent.Disconnect -> {
                    phase = SessionPhase.disconnected
                    return listOf(SessionAction.stopHeartbeat, SessionAction.scheduleReconnect)
                }
                is SessionEvent.PingTimeout -> {
                    phase = SessionPhase.disconnected
                    return listOf(
                        SessionAction.stopHeartbeat,
                        SessionAction.closeConnection,
                        SessionAction.scheduleReconnect,
                    )
                }
                else -> Unit
            }

            SessionPhase.closing -> when (event) {
                is SessionEvent.Close -> {
                    phase = SessionPhase.closed
                    return listOf(SessionAction.stopHeartbeat)
                }
                else -> Unit
            }

            SessionPhase.closed -> Unit
        }
        return emptyList()
    }

    private fun onTlsFailed(reason: CloseReason): List<SessionAction> {
        if (reason == CloseReason.pinMismatch) {
            phase = SessionPhase.closed
            return listOf(SessionAction.stopReconnect)
        }
        phase = SessionPhase.disconnected
        return listOf(SessionAction.scheduleReconnect)
    }
}

/** Exponential backoff with jitter. [jitter] returns a value in `0..upperBound`. */
class ReconnectBackoff(
    private val jitter: (Double) -> Double = { upper -> Math.random() * upper },
) {
    private var index = 0

    fun nextDelay(): Double {
        val base = SCHEDULE[minOf(index, SCHEDULE.lastIndex)]
        index += 1
        val maximumJitter = base * 0.2
        return base + jitter(maximumJitter).coerceIn(0.0, maximumJitter)
    }

    fun reset() {
        index = 0
    }

    companion object {
        private val SCHEDULE = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0)
    }
}

/** Heartbeat timing: ping every 20 s, dead after 60 s of silence. */
class Heartbeat(now: Long) {
    private var lastPing = now
    private var lastActivity = now

    fun shouldPing(now: Long): Boolean {
        if (now - lastPing < INTERVAL_MILLIS) return false
        lastPing = now
        return true
    }

    fun noteActivity(now: Long) {
        lastActivity = now
    }

    fun isDead(now: Long): Boolean = now - lastActivity >= TIMEOUT_MILLIS

    companion object {
        const val INTERVAL_MILLIS = 20_000L
        const val TIMEOUT_MILLIS = 60_000L
    }
}
