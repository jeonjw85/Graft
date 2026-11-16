package kr.jjw.graft.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.TransportCloseReason
import kr.jjw.graft.files.TransferProgress

data class ConnectionUiState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val peerName: String? = null,
    val transfers: Map<UInt, TransferProgress> = emptyMap(),
    val lastError: String? = null,
    val lastClipboard: String? = null,
)

/**
 * The single source of truth for connection state.
 *
 * The foreground service writes; the UI observes. Keeping sockets out of the Activity is what lets
 * the connection survive the screen turning off.
 */
object ConnectionRepository {
    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var pairedName: String? = null

    fun reset() {
        pairedName = null
        _state.value = ConnectionUiState()
    }

    fun onPeerPaired(name: String) {
        pairedName = name
    }

    fun onEvent(event: ConnectionEvent) {
        when (event) {
            ConnectionEvent.Connecting -> _state.update {
                it.copy(status = ConnectionStatus.Connecting, lastError = null)
            }

            is ConnectionEvent.Secure -> _state.update {
                it.copy(status = ConnectionStatus.Connecting, lastError = null)
            }

            is ConnectionEvent.Message -> onMessage(event.envelope)

            is ConnectionEvent.Binary -> {
                // File data is tracked through transfer progress, not connection status.
            }

            is ConnectionEvent.Closed -> onClosed(event.reason)
        }
    }

    fun onTransferProgress(progress: Map<UInt, TransferProgress>) {
        _state.update { it.copy(transfers = progress) }
    }

    fun onClipboardReceived(text: String) {
        _state.update { it.copy(lastClipboard = text) }
    }

    /** Records text the user asked to send, so the screen can show what was last shared. */
    fun onSubmitClipboard(text: String) {
        _state.update { it.copy(lastClipboard = text) }
    }

    fun onStopped() {
        pairedName = null
        _state.value = ConnectionUiState()
    }

    private fun onMessage(envelope: Envelope) {
        when (envelope.type) {
            "hello.ok", "pair.ok" -> _state.update {
                it.copy(
                    status = ConnectionStatus.Connected(pairedName ?: "your Mac"),
                    peerName = pairedName,
                    lastError = null,
                )
            }

            "error" -> {
                val message = runCatching {
                    envelope.payload.let { payload ->
                        (payload as? kotlinx.serialization.json.JsonObject)
                            ?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    }
                }.getOrNull()
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Failed(message ?: "The Mac refused the connection"),
                        lastError = message ?: "The Mac refused the connection",
                    )
                }
            }

            else -> Unit
        }
    }

    private fun onClosed(reason: TransportCloseReason) {
        when (reason) {
            TransportCloseReason.PIN_MISMATCH -> _state.update {
                it.copy(
                    status = ConnectionStatus.Failed("Re-pair required"),
                    lastError = "Re-pair required",
                )
            }

            TransportCloseReason.NORMAL -> _state.update {
                it.copy(status = ConnectionStatus.Idle, peerName = null, lastError = null)
            }

            TransportCloseReason.NETWORK, TransportCloseReason.UNKNOWN -> _state.update {
                it.copy(
                    status = ConnectionStatus.Connecting,
                    lastError = "Connection lost, retrying",
                )
            }
        }
    }
}
