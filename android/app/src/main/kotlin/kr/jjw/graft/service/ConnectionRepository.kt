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
    /** Text the user asked to send, kept apart from what was received. */
    val lastSentClipboard: String? = null,
    /** Wall-clock arrival time of [lastClipboard], so the screen can ignore a stale value. */
    val lastClipboardAt: Long = 0L,
    /** Set when the last attempt to send a file failed, so the screen can show why. */
    val outgoingError: String? = null,
    val recentNotifications: List<kr.jjw.graft.notifications.PostedNotification> = emptyList(),
    /** Finished transfers, newest first. */
    val history: List<TransferRecord> = emptyList(),
)

/**
 * The single source of truth for connection state.
 *
 * The foreground service writes; the UI observes. Keeping sockets out of the Activity is what lets
 * the connection survive the screen turning off.
 */
object ConnectionRepository {
    private const val MAX_PENDING = 100

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var pairedName: String? = null
    private val transferHistory = TransferHistory()
    private var historyStore: TransferHistoryStore? = null

    /**
     * Points the repository at the saved history and folds it into what is already in memory.
     *
     * Both the service and the UI call this, and the UI may do so again after the Activity is
     * recreated. Merging makes a repeat call harmless instead of dropping rows recorded since the
     * first call, and keeping the newest row per id stops the saved copy from duplicating them.
     */
    fun attachHistoryStore(store: TransferHistoryStore) {
        historyStore = store
        val restored = store.load()
        if (restored.isEmpty()) return
        _state.update { it.copy(history = mergeHistory(it.history, restored)) }
    }

    /**
     * Drops in-memory state, as when the service starts over.
     *
     * The saved transfer history survives: a restart is exactly when the user wants to see where the
     * last file went. Only [forgetHistory] deletes it.
     */
    fun reset() {
        pairedName = null
        transferHistory.clear()
        _state.update { it.withoutSession() }
    }

    /** Deletes the saved history as well, for "Forget this Mac". */
    fun forgetHistory() {
        transferHistory.clear()
        historyStore?.clear()
        _state.update { it.copy(history = emptyList()) }
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
        // Snapshot: the coordinator mutates its map in place, so holding the reference would make
        // the new state equal to the old one and swallow every subsequent progress update.
        _state.update { it.copy(transfers = progress.toMap()) }
    }

    /** Records the outcome of sending a file from the phone. */
    fun onOutgoingFileResult(success: Boolean, message: String?) {
        _state.update {
            it.copy(outgoingError = if (success) null else (message ?: "Could not send the file"))
        }
    }

    /** Records a finished transfer once, so the Files tab can show it. */
    fun onTransferFinished(
        id: UInt,
        name: String,
        bytes: Long,
        direction: kr.jjw.graft.core.TransferDirection,
        error: kr.jjw.graft.files.TransferFailureKind?,
        destination: String?,
    ) {
        val row = transferHistory.record(
            id = id,
            name = name,
            bytes = bytes,
            direction = direction,
            error = error,
            destination = destination,
        )
        // Merge with what was restored from disk so a restart does not drop older rows.
        val merged = mergeHistory(listOf(row), _state.value.history)
        _state.update { it.copy(history = merged) }
        historyStore?.save(merged)
    }

    fun onClipboardReceived(text: String) {
        _state.update { it.copy(lastClipboard = text, lastClipboardAt = System.currentTimeMillis()) }
    }

    /** Records text the user asked to send, so the screen can show what was last shared. */
    fun onSubmitClipboard(text: String) {
        _state.update { it.copy(lastSentClipboard = text) }
    }


    private val pending = java.util.concurrent.ConcurrentLinkedQueue<kr.jjw.graft.notifications.PostedNotification>()

    /**
     * Invoked when a notification is queued while a connection is live.
     *
     * Without this the queue only drained when a new session said hello, so an alert posted while
     * already connected waited for a reconnect to reach the Mac.
     */
    @Volatile
    var onPendingNotification: (() -> Unit)? = null

    /**
     * Queues a notification the listener accepted. It is drained when a connection is ready; the
     * listener cannot reach the socket itself.
     */
    fun onNotification(notification: kr.jjw.graft.notifications.PostedNotification) {
        if (pending.size >= MAX_PENDING) pending.poll()
        pending.add(notification)
        _state.update {
            it.copy(recentNotifications = (listOf(notification) + it.recentNotifications).take(50))
        }
        onPendingNotification?.invoke()
    }

    /** Removes and returns everything queued for sending. */
    fun takePendingNotifications(): List<kr.jjw.graft.notifications.PostedNotification> {
        val drained = mutableListOf<kr.jjw.graft.notifications.PostedNotification>()
        while (true) {
            drained.add(pending.poll() ?: break)
        }
        return drained
    }

    fun onStopped() {
        pairedName = null
        _state.update { it.withoutSession() }
    }

    /**
     * Drops what belongs to a live link but keeps the parts that outlive it.
     *
     * Transfer history and the local alert log are about this device, not this session, so a
     * disconnect must not make the Files tab look empty.
     */
    private fun ConnectionUiState.withoutSession() = copy(
        status = ConnectionStatus.Idle,
        peerName = null,
        transfers = emptyMap(),
        lastError = null,
        lastClipboard = null,
        lastSentClipboard = null,
        lastClipboardAt = 0L,
        outgoingError = null,
    )

    /**
     * Newest first, one row per transfer, capped at what the store keeps.
     *
     * The key includes the name and direction because transfer ids are only unique inside one
     * session: the Mac restarts its counter at 1 on every launch.
     */
    private fun mergeHistory(vararg lists: List<TransferRecord>): List<TransferRecord> =
        lists.flatMap { it }
            .sortedByDescending { it.finishedAtMillis }
            .distinctBy { Triple(it.id, it.name, it.direction) }
            .take(TRANSFER_HISTORY_LIMIT)

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
