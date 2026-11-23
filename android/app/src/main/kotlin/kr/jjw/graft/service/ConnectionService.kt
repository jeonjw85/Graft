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
    private val outgoingTempFiles = java.util.concurrent.ConcurrentHashMap<UInt, File>()
    private val pendingSharedFiles = java.util.concurrent.ConcurrentLinkedQueue<PendingSharedFile>()
    private val pendingSharedText = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private lateinit var phoneRinger: PhoneRinger
    private var batteryReceiver: android.content.BroadcastReceiver? = null
    private var lastReportedBattery: Pair<Int, Boolean>? = null

    private data class PendingSharedFile(val file: File, val mime: String)

    override fun onCreate() {
        super.onCreate()
        instance = this
        phoneRinger = PhoneRinger(this, scope)
        createChannel()
        // The service can outlive the Activity, and a sticky restart has no UI at all, so it needs
        // its own handle on the file. Attaching twice is safe: the repository merges.
        scope.launch {
            ConnectionRepository.attachHistoryStore(
                TransferHistoryStore(File(filesDir, "transfer-history.json"))
            )
            // Purge cached outgoing copies left behind by a previous process kill.
            File(cacheDir, "outgoing").listFiles()?.forEach { it.delete() }
        }
        networkMonitor = NetworkMonitor(this) { wake.trySend(Unit) }.also { it.start() }
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {
            ACTION_SEND_FILE -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("userStopped", false)
                    .apply()
                val uri = readParcelableUri(intent, EXTRA_URI)
                startConnection()
                if (uri != null) scope.launch { sendPickedFile(uri) }
                return START_STICKY
            }

            ACTION_SEND_FILES -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("userStopped", false)
                    .apply()
                val uris = readParcelableUriList(intent, EXTRA_URIS)
                startConnection()
                if (uris.isNotEmpty()) {
                    scope.launch { uris.forEach { sendPickedFile(it) } }
                }
                return START_STICKY
            }

            ACTION_SEND_TEXT -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("userStopped", false)
                    .apply()
                val text = intent.getStringExtra(EXTRA_TEXT)
                startConnection()
                if (!text.isNullOrBlank()) {
                    scope.launch { sendSharedTextNowOrQueue(text) }
                }
                return START_STICKY
            }

            ACTION_STOP_RING -> {
                phoneRinger.stop()
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
        unregisterBatteryMonitor()
        phoneRinger.stop()
        networkMonitor?.stop()
        networkMonitor = null
        stopConnection()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Copies the picked document into the cache immediately (while the temporary read grant is
     * valid) and offers it to the Mac right away or as soon as the connection reaches `hello.ok`.
     */
    private suspend fun sendPickedFile(uri: android.net.Uri) = withContext(Dispatchers.IO) {
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
            target.delete()
            ConnectionRepository.onOutgoingFileResult(false, "Could not read the file")
            return@withContext
        }

        val mime = contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(target.extension.lowercase())
            ?: "application/octet-stream"

        val sessionCoordinator = coordinator
        if (sessionCoordinator == null ||
            ConnectionRepository.state.value.status !is ConnectionStatus.Connected
        ) {
            if (!isPaired(this@ConnectionService)) {
                target.delete()
                ConnectionRepository.onOutgoingFileResult(false, "Connect to your Mac first")
                return@withContext
            }
            // Paired but still connecting (e.g., cold start from the Share Sheet): queue until hello.ok.
            pendingSharedFiles.add(PendingSharedFile(target, mime))
            ConnectionRepository.onOutgoingFileResult(true, null)
            return@withContext
        }

        offerCachedFile(sessionCoordinator, target, mime)
    }

    private fun offerCachedFile(
        sessionCoordinator: TransferCoordinator,
        target: File,
        mime: String,
    ) {
        val offered = runCatching {
            // Ids restart at 1 in every session, so include the saved rows when picking the next
            // one. Otherwise a new transfer collides with yesterday's and replaces its row.
            val usedIds = sessionCoordinator.progressSnapshot().keys +
                ConnectionRepository.state.value.history.map { it.id }
            val transferId = TransferIds.next(usedIds)
            outgoingTempFiles[transferId] = target
            sessionCoordinator.offer(target, transferId, mime = mime)
            transferId
        }
        if (offered.isFailure) {
            outgoingTempFiles.entries.removeIf { it.value == target }
            target.delete()
            ConnectionRepository.onOutgoingFileResult(false, "Could not start the transfer")
        } else {
            ConnectionRepository.onTransferProgress(sessionCoordinator.progressSnapshot())
            ConnectionRepository.onOutgoingFileResult(true, null)
        }
    }

    private fun sendSharedTextNowOrQueue(text: String) {
        val activeClient = client
        if (activeClient != null && ConnectionRepository.state.value.status is ConnectionStatus.Connected) {
            val envelope = clipboard.outgoing(text) ?: return
            activeClient.send(envelope)
            ConnectionRepository.onSubmitClipboard(text)
        } else if (isPaired(this)) {
            pendingSharedText.add(text)
        }
    }

    private fun flushPendingShares(
        sessionClient: GraftClient,
        sessionCoordinator: TransferCoordinator,
    ) {
        while (true) {
            val item = pendingSharedFiles.poll() ?: break
            if (item.file.exists()) {
                offerCachedFile(sessionCoordinator, item.file, item.mime)
            }
        }
        while (true) {
            val text = pendingSharedText.poll() ?: break
            val envelope = clipboard.outgoing(text) ?: continue
            sessionClient.send(envelope)
            ConnectionRepository.onSubmitClipboard(text)
        }
    }

    @Suppress("DEPRECATION")
    private fun readParcelableUri(intent: Intent, key: String): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, android.net.Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun readParcelableUriList(intent: Intent, key: String): List<android.net.Uri> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, android.net.Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
        return list?.filterNotNull().orEmpty()
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
        lateinit var sessionCoordinator: TransferCoordinator
        // Each transfer is recorded when its outcome changes, so a failed retry that later commits
        // updates the row instead of leaving it marked as failed.
        val recordedOutcomes = mutableMapOf<UInt, TransferFailureKind?>()
        val outcomeLock = Any()
        fun recordFinishedTransfers() {
            val (completedIds, failureMap) = sessionCoordinator.finishedSnapshot()
            val progressMap = sessionCoordinator.progressSnapshot()
            val finished = completedIds + failureMap.keys
            synchronized(outcomeLock) {
                for (id in finished) {
                    val error = failureMap[id]
                    if (recordedOutcomes.containsKey(id) && recordedOutcomes[id] == error) continue
                    recordedOutcomes[id] = error
                    val progress = progressMap[id]
                    outgoingTempFiles.remove(id)?.delete()
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
        }

        sessionCoordinator = TransferCoordinator(
            directory = directory,
            isPaired = { true },
            // Without this the resume lookups always miss and an interrupted transfer restarts.
            records = kr.jjw.graft.files.TransferRecordStore(File(filesDir, "transfers.json")),
            sendControl = { envelope -> sessionClient.send(envelope) },
            sendBinary = { bytes -> sessionClient.sendBinary(bytes) },
            backlog = { sessionClient.pendingBytes() },
            receiversFactory = FallbackFileReceivingFactory.of(
                primary = MediaStoreFileReceivingFactory.forResolver(contentResolver),
                fallback = DirectoryFileReceiving.factory(directory),
            ),
            sendRunner = { block -> scope.launch(Dispatchers.IO) { block() } },
            onProgressChanged = {
                ConnectionRepository.onTransferProgress(sessionCoordinator.progressSnapshot())
                recordFinishedTransfers()
            },
        )
        coordinator = sessionCoordinator

        ConnectionRepository.onEvent(ConnectionEvent.Connecting)
        var outcome = Outcome.RETRY
        var closed = false
        val stallWatch = scope.launch {
            while (true) {
                delay(5_000)
                if (sessionCoordinator.checkStalls()) {
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progressSnapshot())
                    recordFinishedTransfers()
                }
            }
        }

        try {
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
                        "ping" -> sessionClient.send(
                            Envelope.create("pong", re = event.envelope.id)
                        )

                        "pair.ok" -> sessionClient.send(ClientHandshake.hello())

                        "hello.ok" -> {
                            retryAttempt = 0
                            completeManualPairing(prefixTrust, offer)
                            completePinnedPairing(offer)
                            // Alerts that arrive while connected must go out immediately.
                            ConnectionRepository.onPendingNotification = {
                                flushPendingNotifications(sessionClient)
                            }
                            flushPendingNotifications(sessionClient)
                            flushPendingShares(sessionClient, sessionCoordinator)
                            registerBatteryMonitor(sessionClient)
                            stored?.let { ConnectionRepository.onPeerPaired(it.name) }
                            updateNotification(stored?.name ?: "Connected", connected = true)
                        }

                        "device.ring" -> {
                            val action = runCatching {
                                event.envelope.payload.let {
                                    (it as? kotlinx.serialization.json.JsonObject)
                                        ?.get("action")
                                        ?.let { v -> (v as? JsonPrimitive)?.content }
                                }
                            }.getOrNull()
                            if (action == "stop") phoneRinger.stop() else phoneRinger.start()
                        }

                        "notification.dismiss" -> {
                            val key = runCatching {
                                (event.envelope.payload as? kotlinx.serialization.json.JsonObject)
                                    ?.get("key")
                                    ?.let { (it as? JsonPrimitive)?.content }
                            }.getOrNull()
                            if (!key.isNullOrBlank()) {
                                kr.jjw.graft.notifications.GraftNotificationListener.dismiss(key)
                            }
                        }

                        "notification.reply" -> {
                            val obj = event.envelope.payload as? kotlinx.serialization.json.JsonObject
                            val key = (obj?.get("key") as? JsonPrimitive)?.content
                            val text = (obj?.get("text") as? JsonPrimitive)?.content
                            if (!key.isNullOrBlank() && !text.isNullOrBlank()) {
                                kr.jjw.graft.notifications.GraftNotificationListener.reply(key, text)
                            }
                        }

                        else -> Unit
                    }
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progressSnapshot())
                    recordFinishedTransfers()
                }

                is ConnectionEvent.Secure -> {
                    // TLS is up, so the handshake can start. Without this both sides wait: the Mac
                    // for pair.request, the app for pair.ok.
                    sessionClient.send(
                        ClientHandshake.initial(
                            paired = hasCompletedPairing(this@ConnectionService),
                            token = offer.token,
                            deviceId = deviceId(),
                            name = android.os.Build.MODEL,
                        )
                    )
                }

                is ConnectionEvent.Binary -> {
                    sessionCoordinator.onBinary(event.data)
                    ConnectionRepository.onTransferProgress(sessionCoordinator.progressSnapshot())
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
        } finally {
            // Cancellation skips the normal path; without this, receivers stay pending and
            // filesDir/received keeps its .part files.
            stallWatch.cancel()
            unregisterBatteryMonitor()
            phoneRinger.stop()
            ConnectionRepository.onPendingNotification = null
            sessionCoordinator.cancelAll()
            outgoingTempFiles.values.forEach { it.delete() }
            outgoingTempFiles.clear()
            sessionClient.close()
            client = null
            coordinator = null
        }

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
                        put("replyable", JsonPrimitive(notification.replyable))
                    }
                )
            )
        }
        for (key in ConnectionRepository.takePendingRemovedNotifications()) {
            sessionClient.send(
                Envelope.create(
                    "notification.removed",
                    buildJsonObject { put("key", JsonPrimitive(key)) }
                )
            )
        }
    }

    private fun registerBatteryMonitor(sessionClient: GraftClient) {
        unregisterBatteryMonitor()
        lastReportedBattery = null
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                sendBatteryStatus(sessionClient, intent)
            }
        }
        batteryReceiver = receiver
        val sticky = registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            sendBatteryStatus(sessionClient, sticky)
        }
    }

    private fun unregisterBatteryMonitor() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        lastReportedBattery = null
    }

    private fun sendBatteryStatus(sessionClient: GraftClient, intent: Intent) {
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = ((level * 100) / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val pair = percent to charging
        if (lastReportedBattery == pair) return
        lastReportedBattery = pair
        sessionClient.send(
            Envelope.create(
                "device.status",
                buildJsonObject {
                    put("battery", JsonPrimitive(percent))
                    put("charging", JsonPrimitive(charging))
                }
            )
        )
    }

    private fun stopConnection() {
        connectJob?.cancel()
        connectJob = null
        coordinator?.cancelAll()
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
                    .putBoolean(KEY_PAIRING_COMPLETE, true)
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

    /**
     * Finishes a QR pairing: the fingerprint was already known from the code, and the Mac has now
     * accepted it. Only here does the phone start treating itself as paired.
     */
    private fun completePinnedPairing(offer: PairingOffer) {
        if (hasCompletedPairing(this)) return
        if (offer.fingerprint.isEmpty()) return

        val store = PeerStore(File(filesDir, "peers.json"))
        if (store.load().none { it.fingerprint == offer.fingerprint }) {
            store.add(
                kr.jjw.graft.data.PairedPeer(
                    fingerprint = offer.fingerprint,
                    name = offer.name,
                    platform = "macos",
                    pairedAtMillis = System.currentTimeMillis(),
                )
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_PAIRING_COMPLETE, true)
            .remove("token")
            .apply()
        ConnectionRepository.onPeerPaired(offer.name)
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

    private fun buildNotification(text: String, connected: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GraftActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = ConnectionNotificationState.forStatus(text, null, transferring = false)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Graft")
            .setContentText(state.text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(state.ongoing)
            .setOnlyAlertOnce(true)
        if (connected) {
            val sendClip = PendingIntent.getActivity(
                this,
                1,
                Intent(this, kr.jjw.graft.ui.SendClipboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(kr.jjw.graft.R.string.tile_send_clipboard),
                    sendClip,
                ).build()
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String, connected: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, connected = connected))
    }

    private enum class Outcome { RETRY, STOP }

    companion object {
        const val ACTION_CONNECT = "kr.jjw.graft.CONNECT"
        const val ACTION_DISCONNECT = "kr.jjw.graft.DISCONNECT"
        const val PREFS = "graft-peer"
        const val ACTION_SEND_FILE = "kr.jjw.graft.action.SEND_FILE"
        const val ACTION_SEND_FILES = "kr.jjw.graft.action.SEND_FILES"
        const val ACTION_SEND_TEXT = "kr.jjw.graft.action.SEND_TEXT"
        const val ACTION_STOP_RING = "kr.jjw.graft.action.STOP_RING"
        const val EXTRA_URI = "uri"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "graft-connection"
        private const val KEY_PAIRING_COMPLETE = "pairingComplete"
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

        /**
         * True only once the Mac has accepted the pairing.
         *
         * A fingerprint saved by a QR scan means "this is the Mac we are about to pair with", not
         * "we are paired". Sending `hello` on the strength of the scan made the Mac refuse the very
         * first connection, so the handshake decision reads this flag instead.
         */
        fun hasCompletedPairing(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            // Once the key exists, a false value means "pairing has not finished" and must stay
            // false: the QR and code paths both clear it before dialing.
            val completed =
                if (prefs.contains(KEY_PAIRING_COMPLETE)) prefs.getBoolean(KEY_PAIRING_COMPLETE, false)
                else null
            val pinned = prefs.getString("fingerprint", null)
            // Only read the peer file when the flag is missing (a build that predates it).
            val known = if (completed == null && !pinned.isNullOrEmpty()) {
                runCatching {
                    PeerStore(File(context.filesDir, "peers.json")).load()
                        .map { it.fingerprint }
                        .toSet()
                }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val result = PairingState.hasCompletedPairing(completed, pinned, known)
            if (result && completed == null) {
                prefs.edit().putBoolean(KEY_PAIRING_COMPLETE, true).apply()
            }
            return result
        }

        /** Stores a typed-code offer and drops the old pin, so the next handshake asks to pair. */
        fun saveManualPairing(
            context: Context,
            host: String,
            port: Int,
            prefix: String,
            token: String,
        ) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("prefix", prefix)
                .putString("token", token)
                .remove("fingerprint")
                .putBoolean(KEY_PAIRING_COMPLETE, false)
                .apply()
            File(context.filesDir, "peers.json").delete()
        }

        /** Drops the pairing and stops the service, so the app returns to onboarding. */
        fun forget(context: Context) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove("host")
                .remove("port")
                .remove("fingerprint")
                .remove("prefix")
                .remove("token")
                .putBoolean(KEY_PAIRING_COMPLETE, false)
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
                .putBoolean(KEY_PAIRING_COMPLETE, false)
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

        /** Cancels an active transfer by its protocol id and removes its temp cache file if any. */
        fun cancelTransfer(id: UInt) {
            val service = instance ?: return
            val active = service.coordinator ?: return
            active.cancel(id)
            service.outgoingTempFiles.remove(id)?.delete()
            ConnectionRepository.onTransferProgress(active.progressSnapshot())
        }

        /** Asks the service to offer a document to the Mac. */
        fun sendFile(context: Context, uri: android.net.Uri) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_SEND_FILE
                putExtra(EXTRA_URI, uri)
                clipData = android.content.ClipData.newRawUri("file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }

        /** Asks the service to offer multiple shared documents to the Mac. */
        fun sendFiles(context: Context, uris: List<android.net.Uri>) {
            if (uris.isEmpty()) return
            val clip = android.content.ClipData.newRawUri("files", uris.first()).apply {
                for (index in 1 until uris.size) {
                    addItem(android.content.ClipData.Item(uris[index]))
                }
            }
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_SEND_FILES
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }

        /** Sends shared text (such as a URL or note from the Share Sheet) to the Mac clipboard. */
        fun sendSharedText(context: Context, text: String) {
            if (text.isBlank()) return
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_SEND_TEXT
                putExtra(EXTRA_TEXT, text)
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
