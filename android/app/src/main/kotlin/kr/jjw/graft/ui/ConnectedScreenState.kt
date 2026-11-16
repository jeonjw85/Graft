package kr.jjw.graft.ui

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress
import kr.jjw.graft.service.ConnectionStatus
import kr.jjw.graft.service.ConnectionUiState

/** Derived state for the connected screen, kept free of Android types so it is unit testable. */
data class ConnectedScreenState(
    val canSendClipboard: Boolean,
    val lastReceived: String?,
    val clipboardNote: String?,
    val statusLabel: String,
    val transfers: List<TransferProgress>,
) {
    companion object {
        fun from(state: ConnectionUiState): ConnectedScreenState {
            val connected = state.status is ConnectionStatus.Connected
            return ConnectedScreenState(
                canSendClipboard = connected,
                lastReceived = state.lastClipboard,
                clipboardNote = if (connected) {
                    "Copying on Android is manual: press Send after copying."
                } else {
                    null
                },
                statusLabel = when (val status = state.status) {
                    is ConnectionStatus.Connected -> "Connected to ${state.peerName ?: "your Mac"}"
                    is ConnectionStatus.Connecting -> state.lastError ?: "Connecting…"
                    is ConnectionStatus.Failed -> status.message
                    ConnectionStatus.Idle -> "Not connected"
                },
                transfers = state.transfers.values.sortedBy { it.name },
            )
        }
    }
}
