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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.PairingOffer
import kr.jjw.graft.core.identity.DeviceIdentity
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.GraftClient
import kr.jjw.graft.core.transport.IdentityKeyManager
import kr.jjw.graft.core.ClientHandshake
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.core.transport.PinnedTrustManager
import kr.jjw.graft.core.transport.PrefixTrustManager
import kr.jjw.graft.core.identity.ManualPairingDecision
import kr.jjw.graft.core.transport.PinningTrustManager
import kr.jjw.graft.core.transport.TransportCloseReason
import kr.jjw.graft.data.AndroidKeyStoreBackend
import kr.jjw.graft.data.PeerStore
import kr.jjw.graft.files.DirectoryFileReceiving
import kr.jjw.graft.files.TransferCoordinator
import kr.jjw.graft.files.FallbackFileReceivingFactory
import kr.jjw.graft.files.MediaStoreFileReceivingFactory
import kr.jjw.graft.files.SafeFilename
import kr.jjw.graft.net.BonjourDiscovery
import kr.jjw.graft.net.DiscoveredHost
import kr.jjw.graft.net.NetworkMonitor
import kr.jjw.graft.files.TransferFailureKind
import kr.jjw.graft.files.TransferIds
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
    private var discoveryJob: Job? = null
    @Volatile private var discoveredTarget: DiscoveredHost? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var networkMonitor: NetworkMonitor? = null
    private var client: GraftClient? = null
    private var coordinator: TransferCoordinator? = null
    private var retryAttempt = 0
    private val clipboard = kr.jjw.graft.clipboard.ClipboardBridge()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        ConnectionRepository.attachHistoryStore(
            TransferHistoryStore(File(filesDir, "transfer-history.json"))
        )
        networkMonitor = NetworkMonitor(this) { wake.trySend(Unit) }.also { it.start() }
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_SEND_FILE -> {
                val uri = intent.getParcelableExtra(EXTRA_URI, android.net.Uri::class.java)
                startConnection()
                if (uri != null) scope.launch { sendPickedFile(uri) }
                return START_STICKY
            }

            ACTION_DISCONNECT -> {
                // Remembered so reopening the app does not immediately reconnect.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("userStopped", true).apply()
                stopConnection()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // Connecting again is the user's choice; clear the stopped flag.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("userStopped", false).apply()
                startConnection()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        instance = null
        networkMonitor?.stop()
        networkMonitor = null
        stopConnection()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copies the picked document into the cache and offers it to the Mac.
     *
     * The transfer coordinator works with files, not content URIs, and the document provider may
     * revoke access once the picker returns; copying first keeps the transfer independent of that.
     */
    private suspend fun sendPickedFile(uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val sessionCoordinator = coordinator
        if (sessionCoordinator == null ||
            ConnectionRepository.state.value.status !is ConnectionStatus.Connected
        ) {
            ConnectionRepository.onOutgoingFileResult(false, "Connect to your Mac first")
            return@withContext
        }

        val directory = File(cacheDir, "outgoing").apply { mkdirs() }
        // A provider can report any display name; keep it inside the cache directory.
        val name = SafeFilename.sanitize(displayName(uri) ?: "") ?: "file"
        val target = File(directory, SafeFilename.unique(directory, name))

        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("the provider returned no stream")
        }
        if (copied.isFailure) {
            ConnectionRepository.onOutgoingFileResult(false, "Could not read the file")
            return@withContext
        }

        val offered = runCatching {
            sessionCoordinator.offer(target, TransferIds.next(sessionCoordinator.progress.keys))
        }
        if (offered.isFailure) {
            ConnectionRepository.onOutgoingFileResult(false, "Could not start the transfer")
        } else {
            ConnectionRepository.onOutgoingFileResult(true, null)
        }
    }

    /** A stable, app-scoped id the Mac can show; the Android ID is enough and needs no permission. */
    private fun deviceId(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "android"

    private fun displayName(uri: android.net.Uri): String? =
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    private fun startConnection() {
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {
            var offer = loadPairingOffer() ?: run {
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

                // Use an address found by a background probe. The probe itself must never block
                // this loop: some devices' NSD blocks its caller when mDNS is filtered.
                discoveredTarget?.let { found ->
                    discoveredTarget = null
                    if (found.host != offer.host || found.port != offer.port) {
                        offer = offer.copy(host = found.host, port = found.port)
                        saveOfferAddress(offer)
                        retryAttempt = 0
                        updateNotification("Found your Mac at ${found.host}")
                    }
                }
                startDiscoveryProbe(offer)

                // A network that comes back should retry now, not after a minute of backoff.
                withTimeoutOrNull(waitMillis) { wake.receive() }
            }
        }
    }

    /** Looks for the trusted Mac in the background and keeps the newest address for the retry. */
    private fun startDiscoveryProbe(offer: PairingOffer) {
        if (discoveryJob?.isActive == true) return

        val stored = PeerStore(File(filesDir, "peers.json")).load().firstOrNull()
        val pinned = offer.fingerprint.ifEmpty { stored?.fingerprint ?: "" }
        val prefix = if (pinned.isEmpty()) loadManualPrefix() else null
        if (pinned.isEmpty() && prefix == null) return

        discoveryJob = scope.launch {
            val found = runCatching {
                BonjourDiscovery(applicationContext).find(
                    pinnedFingerprint = pinned.ifEmpty { null },
                    prefix = prefix,
                )
            }.getOrNull()
            if (found != null) discoveredTarget = found
        }
    }

    private fun saveOfferAddress(offer: PairingOffer) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("host", offer.host)
            .putInt("port", offer.port)
            .apply()
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
            receiversFactory = FallbackFileReceivingFactory.of(
                primary = MediaStoreFileReceivingFactory.forResolver(contentResolver),
                fallback = DirectoryFileReceiving.factory(directory),
            ),
        )
        coordinator = sessionCoordinator

        // Each transfer is recorded when its outcome changes, so a failed retry that later commits
        // updates the row instead of leaving it marked as failed.
        val recordedOutcomes = mutableMapOf<UInt, TransferFailureKind?>()
        fun recordFinishedTransfers() {
            val finished = sessionCoordinator.completed + sessionCoordinator.failures.keys
            for (id in finished) {
                val error = sessionCoordinator.failures[id]
                if (recordedOutcomes.containsKey(id) && recordedOutcomes[id] == error) continue
                recordedOutcomes[id] = error
                val progress = sessionCoordinator.progress[id]
                ConnectionRepository.onTransferFinished(
                    id = id,
                    name = progress?.name ?: "file",
                    bytes = progress?.total ?: progress?.confirmed ?: 0,
                    direction = progress?.direction ?: TransferDirection.receive,
                    error = error,
                    destination = sessionCoordinator.locations[id],
                )
            }
        }

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
                            // Alerts that arrive while connected must go out immediately.
                            ConnectionRepository.onPendingNotification = {
                                flushPendingNotifications(sessionClient)
                            }
                            flushPendingNotifications(sessionClient)
                            stored?.let { ConnectionRepository.onPeerPaired(it.name) }
                            updateNotification(stored?.name ?: "Connected")
                        }

                        else -> Unit
                    }
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progress)
                    recordFinishedTransfers()
                }

                is ConnectionEvent.Secure -> {
                    // TLS is up, so the handshake can start. Without this both sides wait: the Mac
                    // for pair.request, the app for pair.ok.
                    sessionClient.send(
                        ClientHandshake.initial(
                            paired = isPaired(this@ConnectionService),
                            token = offer.token,
                            deviceId = deviceId(),
                            name = android.os.Build.MODEL,
                        )
                    )
                }

                is ConnectionEvent.Binary -> {
                    sessionCoordinator.onBinary(event.data)
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progress)
                    recordFinishedTransfers()
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

        ConnectionRepository.onPendingNotification = null
        sessionCoordinator.cancelAll()
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
                // Record the full pin so the next launch opens the main screen and connects
                // with the same fingerprint QR pairing would have stored.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("fingerprint", decision.fingerprint)
                    .remove("prefix")
                    .apply()
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
            // A permanent link indicator must never make a sound or pop up.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shows that Graft is connected to your Mac"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GraftActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = ConnectionNotificationState.forStatus(text, null, transferring = false)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Graft")
            .setContentText(state.text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(true)
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
        const val ACTION_SEND_FILE = "kr.jjw.graft.action.SEND_FILE"
        const val EXTRA_URI = "uri"
        private const val CHANNEL_ID = "graft-connection"
        const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: ConnectionService? = null

        /** Whether the app should connect as soon as it is opened. */
        fun shouldConnect(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return kr.jjw.graft.ui.AppEntry.shouldConnect(
                paired = isPaired(context),
                prefix = prefs.getString("prefix", null),
                token = prefs.getString("token", null),
                userStopped = prefs.getBoolean("userStopped", false),
            )
        }

        /** True when a pairing offer is saved but not yet confirmed. */
        fun hasPendingOffer(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return !prefs.getString("prefix", null).isNullOrEmpty() &&
                !prefs.getString("token", null).isNullOrEmpty()
        }

        /** True when a peer fingerprint is pinned, from either pairing path. */
        fun isPaired(context: Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString("fingerprint", null)
                ?.isNotEmpty() == true

        /** Drops the pairing and stops the service, so the app returns to onboarding. */
        fun forget(context: Context) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove("host")
                .remove("port")
                .remove("fingerprint")
                .remove("prefix")
                .remove("token")
                .apply()
            File(context.filesDir, "peers.json").delete()
            ConnectionRepository.forgetHistory()
            stop(context)
        }

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
        /** Sends clipboard text and returns a user-facing reason when it could not be sent. */
        fun sendClipboard(text: String?): String? {
            val service = instance ?: return "Connect to your Mac first"
            val client = service.client ?: return "Connect to your Mac first"
            val envelope = service.clipboard.outgoing(text)
                ?: return service.clipboard.error() ?: "Clipboard has no text to send"
            client.send(envelope)
            ConnectionRepository.onSubmitClipboard(text.orEmpty())
            return null
        }

        /** Asks the service to offer a document to the Mac. */
        fun sendFile(context: Context, uri: android.net.Uri) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_SEND_FILE
                putExtra(EXTRA_URI, uri)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ConnectionService::class.java).apply { action = ACTION_DISCONNECT }
            )
        }
    }
}
