package kr.jjw.graft.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Service
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.PairingOffer
import kr.jjw.graft.core.identity.DeviceIdentity
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.GraftClient
import kr.jjw.graft.core.transport.IdentityKeyManager
import kr.jjw.graft.core.transport.PinnedTrustManager
import kr.jjw.graft.core.transport.PrefixTrustManager
import kr.jjw.graft.core.identity.ManualPairingDecision
import kr.jjw.graft.core.transport.PinningTrustManager
import kr.jjw.graft.core.transport.TransportCloseReason
import kr.jjw.graft.data.AndroidKeyStoreBackend
import kr.jjw.graft.data.PeerStore
import kr.jjw.graft.files.DirectoryFileReceiving
import kr.jjw.graft.files.TransferCoordinator
import kr.jjw.graft.ui.GraftActivity

/**
 * Owns the socket for the whole app.
 *
 * Runs as a `connectedDevice` foreground service so retries continue while the screen is off.
 * Android 15 caps `dataSync` services at six hours per day, which would silently kill the
 * connection; `connectedDevice` matches the actual purpose.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var client: GraftClient? = null
    private var coordinator: TransferCoordinator? = null
    private var retryAttempt = 0
    private val clipboard = kr.jjw.graft.clipboard.ClipboardBridge()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopConnection()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startConnection()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        instance = null
        stopConnection()
        scope.cancel()
        super.onDestroy()
    }

    private fun startConnection() {
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {
            val offer = loadPairingOffer()
            if (offer == null) {
                ConnectionRepository.reset()
                updateNotification("Not paired")
                return@launch
            }

            while (true) {
                val outcome = runCatching { connectOnce(offer) }.getOrDefault(Outcome.RETRY)
                if (outcome == Outcome.STOP) break

                retryAttempt += 1
                val waitMillis = backoffMillis(retryAttempt)
                ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.NETWORK))
                updateNotification("Reconnecting in ${waitMillis / 1000}s")
                delay(waitMillis)
            }
        }
    }

    /**
     * Runs one connection to completion. Returns [Outcome.STOP] when retrying would be wrong (a pin
     * mismatch means the user must re-pair).
     */
    private suspend fun connectOnce(offer: PairingOffer): Outcome {
        val identity = DeviceIdentity.loadOrCreate(
            AndroidKeyStoreBackend(applicationContext),
            "graft-identity",
        )
        val peers = PeerStore(File(filesDir, "peers.json"))
        val stored = peers.load().firstOrNull()

        val manualPrefix = loadManualPrefix()
        val pin = offer.fingerprint.ifEmpty { stored?.fingerprint ?: "" }

        val trustManager: PinningTrustManager
        val prefixTrust: PrefixTrustManager?
        if (pin.isNotEmpty()) {
            trustManager = PinnedTrustManager { setOf(pin) }
            prefixTrust = null
        } else if (manualPrefix != null) {
            val prefix = PrefixTrustManager(manualPrefix)
            trustManager = prefix
            prefixTrust = prefix
        } else {
            ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.PIN_MISMATCH))
            return Outcome.STOP
        }

        val sessionClient = GraftClient(
            baseUrl = "wss://${offer.host}:${offer.port}/graft",
            trustManager = trustManager,
            keyManager = IdentityKeyManager(
                "graft",
                identity.privateKey,
                arrayOf(identity.certificate),
            ),
        )
        client = sessionClient

        val directory = File(filesDir, "received")
        val sessionCoordinator = TransferCoordinator(
            directory = directory,
            isPaired = { true },
            sendControl = { envelope -> sessionClient.send(envelope) },
            sendBinary = { bytes -> sessionClient.sendBinary(bytes) },
            receiversFactory = DirectoryFileReceiving.factory(directory),
        )
        coordinator = sessionCoordinator

        ConnectionRepository.onEvent(ConnectionEvent.Connecting)
        var outcome = Outcome.RETRY
        var closed = false

        sessionClient.connect().collect { event ->
            ConnectionRepository.onEvent(event)

            when (event) {
                is ConnectionEvent.Message -> {
                    sessionCoordinator.onControl(event.envelope)

                    if (event.envelope.type == "clipboard.update") {
                        clipboard.onMessage(event.envelope)?.let { text ->
                            ConnectionRepository.onClipboardReceived(text)
                        }
                    }
                    when (event.envelope.type) {
                        "pair.ok" -> sessionClient.send(
                            Envelope.create(
                                "hello",
                                buildJsonObject { put("proto", JsonPrimitive(1)) }
                            )
                        )

                        "hello.ok" -> {
                            retryAttempt = 0
                            completeManualPairing(prefixTrust, offer)
                            flushPendingNotifications(sessionClient)
                            stored?.let { ConnectionRepository.onPeerPaired(it.name) }
                            updateNotification(stored?.name ?: "Connected")
                        }

                        else -> Unit
                    }
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progress)
                }

                is ConnectionEvent.Binary -> {
                    sessionCoordinator.onBinary(event.data)
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progress)
                }

                is ConnectionEvent.Closed -> {
                    closed = true
                    outcome = if (event.reason == TransportCloseReason.PIN_MISMATCH) {
                        Outcome.STOP
                    } else {
                        Outcome.RETRY
                    }
                }

                else -> Unit
            }
        }

        sessionClient.close()
        client = null
        coordinator = null
        // A flow that ends without a close event still means the socket is gone.
        if (!closed) outcome = Outcome.RETRY
        return outcome
    }

    /**
     * Sends notifications the listener collected. The listener runs in its own process context and
     * cannot reach the socket, so it queues here through the repository.
     */
    private fun flushPendingNotifications(sessionClient: GraftClient) {
        for (notification in ConnectionRepository.takePendingNotifications()) {
            sessionClient.send(
                Envelope.create(
                    "notification.posted",
                    buildJsonObject {
                        put("pkg", JsonPrimitive(notification.pkg))
                        put("app", JsonPrimitive(notification.app))
                        put("title", JsonPrimitive(notification.title))
                        put("text", JsonPrimitive(notification.text))
                        put("ts", JsonPrimitive(notification.postedAt))
                        put("key", JsonPrimitive(notification.key))
                        put("priority", JsonPrimitive(notification.priority))
                    }
                )
            )
        }
    }

    private fun stopConnection() {
        connectJob?.cancel()
        connectJob = null
        client?.close()
        client = null
        coordinator = null
        ConnectionRepository.onStopped()
        updateNotification("Disconnected")
    }

    /**
     * After a manual pairing succeeds, store the full fingerprint the handshake verified so later
     * connections use pinning instead of prefix matching.
     */
    private fun completeManualPairing(prefixTrust: PrefixTrustManager?, offer: PairingOffer) {
        val prefix = loadManualPrefix() ?: return
        val verified = prefixTrust?.verifiedFingerprint ?: return

        val store = PeerStore(File(filesDir, "peers.json"))
        val existing = store.load().firstOrNull()

        when (val decision = ManualPairingDecision.decide(existing?.fingerprint, prefix, verified)) {
            is ManualPairingDecision.Result.Pin -> {
                store.add(
                    kr.jjw.graft.data.PairedPeer(
                        fingerprint = decision.fingerprint,
                        name = offer.name,
                        platform = "macos",
                        pairedAtMillis = System.currentTimeMillis(),
                    )
                )
                // Drop the prefix so the next attempt pins the full value.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("prefix").apply()
                ConnectionRepository.onPeerPaired(offer.name)
            }

            is ManualPairingDecision.Result.AlreadyPaired -> {
                ConnectionRepository.onEvent(ConnectionEvent.Closed(TransportCloseReason.PIN_MISMATCH))
            }

            ManualPairingDecision.Result.Unverified,
            is ManualPairingDecision.Result.InvalidPrefix,
            -> Unit
        }
    }

    private fun loadManualPrefix(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString("prefix", null)

    private fun loadPairingOffer(): PairingOffer? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val host = prefs.getString("host", null) ?: return null
        val fingerprint = prefs.getString("fingerprint", null)
        val prefix = prefs.getString("prefix", null)
        if (fingerprint.isNullOrEmpty() && prefix.isNullOrEmpty()) return null
        return PairingOffer(
            version = 1,
            host = host,
            port = prefs.getInt("port", 47800),
            fingerprint = fingerprint ?: "",
            token = prefs.getString("token", "") ?: "",
            name = prefs.getString("name", "Mac") ?: "Mac",
        )
    }

    private fun backoffMillis(attempt: Int): Long {
        val schedule = listOf(1, 2, 4, 8, 16, 32, 60)
        val base = schedule[(attempt - 1).coerceIn(0, schedule.lastIndex)]
        val jitter = base * 0.2 * Math.random()
        return ((base + jitter) * 1000).toLong()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps Graft connected to your Mac" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GraftActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Graft")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private enum class Outcome { RETRY, STOP }

    companion object {
        const val ACTION_CONNECT = "kr.jjw.graft.CONNECT"
        const val ACTION_DISCONNECT = "kr.jjw.graft.DISCONNECT"
        const val PREFS = "graft-peer"
        private const val CHANNEL_ID = "graft-connection"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: ConnectionService? = null

        /** Stores a scanned offer so the service connects with the QR code's pinned fingerprint. */
        fun savePairingOffer(context: Context, offer: PairingOffer) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("host", offer.host)
                .putInt("port", offer.port)
                .putString("fingerprint", offer.fingerprint)
                .putString("token", offer.token)
                .putString("name", offer.name)
                .remove("prefix")
                .apply()
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ConnectionService::class.java).apply { action = ACTION_CONNECT }
            )
        }

        /** Sends the given clipboard text; the caller reads the clipboard while it has focus. */
        fun sendClipboard(text: String) {
            val client = instance?.client ?: return
            val envelope = instance?.clipboard?.outgoing(text) ?: return
            client.send(envelope)
            ConnectionRepository.onSubmitClipboard(text)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ConnectionService::class.java).apply { action = ACTION_DISCONNECT }
            )
        }
    }
}
