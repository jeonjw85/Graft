import AppKit
import SwiftUI
import Foundation
import ServiceManagement
import GraftMacKit
import GraftProtocol

/// Owns the TLS server, the device identity, and the paired peer list.
///
/// Everything the UI needs goes through `@Published` state so SwiftUI can render plain values.
@MainActor
final class GraftService: ObservableObject {
    /// One instance owns the server for the whole process. A second instance would try to bind the
    /// same port and fail.
    static let shared = GraftService()

    @Published private(set) var menuState = MenuState(status: .idle, peerName: nil)
    @Published private(set) var peers: [PairedPeer] = []
    @Published private(set) var pairingURI: String?
    @Published private(set) var qrImage: NSImage?
    @Published private(set) var manualPairing: PairingService.NumericPairing?
    @Published private(set) var transfers: [UInt32: TransferProgress] = [:]
    @Published private(set) var transferMetrics: [UInt32: TransferMetrics] = [:]
    @Published private(set) var transferHistory: [MacTransferRecord] = []

    private let history = TransferHistory()
    private let historyStore: TransferHistoryStore
    private let speedTracker = TransferSpeedTracker()
    private var outgoingTempArchives: [UInt32: URL] = [:]
    private var recordedOutcomes: [String: TransferFailure?] = [:]
    private var pairingGeneration = 0
    private var lastTransferSucceeded = false
    @Published private(set) var transferFailure: String?
    @Published private(set) var receiveDirectory: URL = GraftService.defaultReceiveDirectory()
    @Published private(set) var launchAtLogin = false
    @Published var autoCopyClipboard = true {
        didSet { defaults.set(autoCopyClipboard, forKey: Self.autoCopyKey) }
    }
    @Published var autoShareClipboard = true {
        didSet {
            defaults.set(autoShareClipboard, forKey: Self.autoShareKey)
            if autoShareClipboard {
                clipboard.syncChangeCount()
            }
        }
    }
    @Published var autoSendScreenshots = false {
        didSet {
            defaults.set(autoSendScreenshots, forKey: Self.autoScreenshotsKey)
            if autoSendScreenshots {
                screenshotWatcher.baseline()
            } else {
                screenshotWatcher.reset()
            }
        }
    }
    @Published private(set) var notificationPreviews = true {
        didSet { defaults.set(notificationPreviews, forKey: Self.previewsKey) }
    }

    private static let autoCopyKey = "graft.autoCopyClipboard"
    private static let autoShareKey = "graft.autoShareClipboard"
    private static let autoScreenshotsKey = "graft.autoSendScreenshots"
    private static let previewsKey = "graft.notificationPreviews"
    private static let receiveDirectoryKey = "graft.receiveDirectory"
    @Published private(set) var notificationsAllowed: Bool?
    /// Address the phone is expected at, shown in the popover.
    @Published private(set) var peerAddress: String?
    @Published private(set) var peerStatus: PeerDeviceStatus?
    @Published private(set) var isRingingPeer = false
    @Published private(set) var lastSharedClipboard: String?
    @Published private(set) var recentNotifications: [NotificationPresenter.Recent] = []
    private var ringResetTask: DispatchWorkItem?

    private let port: UInt16
    private let defaults: UserDefaults
    private let store: PeerStore
    private let clock: () -> Date
    private let identity: DeviceIdentity?
    private let pairingService: PairingService?
    private var server: TransportServer?
    private var advertiser: BonjourAdvertiser?
    private var wakeObserver: NSObjectProtocol?
    private var sessions: [ObjectIdentifier: ConnectionSession] = [:]
    private let coordinators = CoordinatorPool()
    private var nextTransferId: UInt32 = 1
    private let clipboard = ClipboardBridge(writer: PasteboardWriter(), reader: PasteboardReader())
    private let screenshotWatcher = ScreenshotWatcher()
    private let notifications = NotificationPresenter(
        port: SystemNotificationCenter(),
        authorization: SystemNotificationAuthorization()
    )

    /// One sentence for the settings window, kept free of AppKit so it can be tested.
    nonisolated static func notificationStatusText(allowed: Bool?) -> String {
        switch allowed {
        case .some(true): return "Allowed"
        case .some(false): return "Notifications are turned off for Graft"
        case .none: return "Not asked yet"
        }
    }

    var notificationStatus: String {
        Self.notificationStatusText(allowed: notificationsAllowed)
    }

    /// The certificate fingerprint the phone pins, so a user can compare it by eye.
    var localFingerprint: String {
        identity?.fingerprint ?? ""
    }

    /// The eight characters a user compares against the phone.
    var fingerprintPrefix: String {
        String(localFingerprint.prefix(8))
    }

    var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return "Graft \(version ?? "0.1.0")"
    }

    func clearRecentNotifications() {
        notifications.clearRecent()
        recentNotifications = []
    }

    /// Shows the received files in Finder.
    func revealReceiveDirectory() {
        try? FileManager.default.createDirectory(at: receiveDirectory, withIntermediateDirectories: true)
        NSWorkspace.shared.activateFileViewerSelecting([receiveDirectory])
    }

    /// Reveals a single received file in Finder when it still exists on disk.
    func revealFile(atPath path: String) {
        let url = URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: path) {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else {
            revealReceiveDirectory()
        }
    }

    /// Updates the destination folder for newly received files and persists the choice.
    func updateReceiveDirectory(_ url: URL) {
        receiveDirectory = url
        defaults.set(url.path, forKey: Self.receiveDirectoryKey)
        for coordinator in coordinators.all {
            coordinator.directory = url
        }
    }

    /// Clears persisted and in-memory transfer history.
    func clearTransferHistory() {
        history.clear()
        historyStore.clear()
        transferHistory = []
    }

    /// Registers or unregisters Graft as a macOS login item via SMAppService.
    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            lastError = error
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    /// Asks for notification permission once, so banners can appear the first time a phone alerts.
    func requestNotificationAuthorization() {
        notifications.onAuthorizationChange = { [weak self] granted in
            DispatchQueue.main.async { self?.notificationsAllowed = granted }
        }
        notifications.requestAuthorizationIfNeeded()
    }

    /// Retries binding the server; the usual cause is another app holding the port until it quits.
    func restart() {
        stop()
        do {
            try start()
        } catch {
            // start() already published the failure; nothing else to do here.
        }
    }

    /// Opens the Local Network privacy pane, which is what a silently failing listener needs.
    func openLocalNetworkSettings() {
        let candidates = [
            "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork",
            "x-apple.systempreferences:com.apple.preference.security?Privacy",
        ]
        for candidate in candidates {
            if let url = URL(string: candidate) {
                NSWorkspace.shared.open(url)
                return
            }
        }
    }

    /// Opens the system pane where a denied notification permission can be re-enabled.
    func openNotificationSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") else {
            return
        }
        NSWorkspace.shared.open(url)
    }

    /// The most recent live connection, used for menu actions such as "Send clipboard".
    private var connection: TransportConnection?
    private var heartbeatTimer: DispatchSourceTimer?
    private(set) var lastError: Error?
    private(set) var hasStarted = false

    /// The peer accepted during this session, if any. Cleared when the user forgets a device so a
    /// later frame cannot silently re-add it.
    private(set) var pendingPairedPeer: (fingerprint: String, name: String)?

    init(
        port: UInt16 = 47800,
        storeURL: URL? = nil,
        historyStoreURL: URL? = nil,
        clock: @escaping () -> Date = Date.init,
        identityTag: String = "kr.jjw.graft.mac",
        defaults: UserDefaults = .standard
    ) {
        self.port = port
        self.clock = clock
        self.defaults = defaults
        let resolvedStoreURL = storeURL ?? Self.defaultStoreURL()
        self.store = PeerStore(url: resolvedStoreURL)
        self.historyStore = TransferHistoryStore(
            file: historyStoreURL ?? resolvedStoreURL
                .deletingLastPathComponent()
                .appendingPathComponent("transfer-history.json")
        )

        do {
            let identity = try DeviceIdentity.loadOrCreate(tag: identityTag)
            self.identity = identity
            self.pairingService = PairingService(local: identity, paired: Set(store.load().map(\.fingerprint)))
        } catch {
            self.identity = nil
            self.pairingService = nil
            self.lastError = error
            self.menuState = MenuState(
                status: .failed(message: "Could not create the security identity"),
                peerName: nil
            )
        }

        self.peers = store.load()
        self.history.restore(historyStore.load())
        self.transferHistory = history.records

        if let savedDir = defaults.string(forKey: Self.receiveDirectoryKey), !savedDir.isEmpty {
            self.receiveDirectory = URL(fileURLWithPath: savedDir, isDirectory: true)
        }
        self.launchAtLogin = SMAppService.mainApp.status == .enabled

        // Read the saved choices on every launch; a privacy toggle that resets itself is a bug.
        let previews = defaults.object(forKey: Self.previewsKey) as? Bool ?? false
        self.notificationPreviews = previews
        self.notifications.showPreviews = previews
        self.autoCopyClipboard = defaults.object(forKey: Self.autoCopyKey) as? Bool ?? true
        self.autoShareClipboard = defaults.object(forKey: Self.autoShareKey) as? Bool ?? true
        self.autoSendScreenshots = defaults.object(forKey: Self.autoScreenshotsKey) as? Bool ?? false
    }

    deinit {
        heartbeatTimer?.cancel()
    }

    var statusSymbol: String {
        switch menuState.status {
        case .connected: return "iphone.radiowaves.left.and.right"
        case .pairing: return "qrcode"
        case .searching: return "magnifyingglass"
        case .failed: return "exclamationmark.triangle"
        case .idle: return "iphone.slash"
        }
    }

    var statusColor: Color {
        Palette.color(for: Palette.tone(for: menuState.status))
    }

    /// The six-digit fallback code, extracted from the pairing URI.
    var fallbackCode: String? {
        guard let pairingURI,
              let components = URLComponents(string: pairingURI),
              let token = components.queryItems?.first(where: { $0.name == "t" })?.value,
              token.count == 6 else {
            return nil
        }
        return token
    }

    // MARK: - Lifecycle

    func start() throws {
        guard let identity, let pairingService else {
            throw TransportError.identityUnavailable
        }

        let authorizer = PeerAuthorizer(
            allowed: { pairingService.allowedFingerprints },
            isPairingOpen: { pairingService.isPairingOpen() }
        )
        let server = TransportServer(identity: identity, authorizer: authorizer, port: port)

        server.onConnection = { [weak self] connection in
            Task { @MainActor in self?.attach(connection: connection) }
        }
        server.onDisconnect = { [weak self] key in
            Task { @MainActor in self?.handleDisconnect(key: key) }
        }

        do {
            try server.start()
        } catch {
            lastError = error
            menuState = MenuState(status: .failed(message: "Port \(port) is in use"), peerName: nil)
            throw error
        }

        self.server = server
        menuState = MenuState(status: .idle, peerName: nil)

        guard let assignedPort = server.port else { return }
        startAdvertising(port: assignedPort, fingerprint: identity.fingerprint)

        // After sleep, Bonjour registrations can go stale; re-publish so the phone can find us
        // again by fingerprint instead of falling back to a possibly changed IP.
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refreshAdvertising() }
        }
    }

    private func startAdvertising(port: UInt16, fingerprint: String) {
        let discoverable = BonjourAdvertiser(
            port: port,
            fingerprint: fingerprint,
            deviceName: Host.current().localizedName ?? "Graft Mac"
        )
        discoverable.onFailure = { [weak self] error in
            Task { @MainActor in
                self?.lastError = error
                // A failed registration means the phone cannot find this Mac after a network
                // change. Saying so beats pretending the link is idle.
                if self?.sessions.isEmpty == true {
                    self?.menuState = MenuState(
                        status: .failed(message: "Could not advertise on this network"),
                        peerName: nil
                    )
                }
            }
        }
        do {
            try discoverable.start()
        } catch {
            lastError = error
            menuState = MenuState(
                status: .failed(message: "Could not advertise on this network"),
                peerName: nil
            )
        }
        advertiser = discoverable
    }

    private func refreshAdvertising() {
        guard let port = server?.port, let fingerprint = identity?.fingerprint else { return }
        advertiser?.stop()
        advertiser = nil
        startAdvertising(port: port, fingerprint: fingerprint)
    }

    func stop() {
        if let wakeObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(wakeObserver)
            self.wakeObserver = nil
        }
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        for session in sessions.values {
            session.tick(now: clock())
        }
        sessions.removeAll()
        coordinators.removeAll()
        connection = nil
        advertiser?.stop()
        advertiser = nil
        server?.stop()
        server = nil
        recordedOutcomes.removeAll()
        lastTransferSucceeded = false
        speedTracker.reset()
        transferMetrics = [:]
        peerStatus = nil
        resetRingState()
        cleanupAllTempArchives()
    }

    func markStarted() {
        hasStarted = true
    }

    func reloadPeers() {
        peers = store.load()
    }

    // MARK: - Pairing

    func beginPairing() {
        pairingGeneration += 1
        let generation = pairingGeneration
        guard pairingReady else {
            // The server binds a moment after launch. A click that beats it must wait, not fail —
            // otherwise the first thing a user sees is an error.
            retryPairing(attempts: 10, generation: generation)
            return
        }
        startPairingOffer()
    }

    private var pairingReady: Bool {
        pairingService != nil && server?.port != nil
    }

    private func retryPairing(attempts: Int, generation: Int) {
        guard attempts > 0 else {
            menuState = MenuState(status: .failed(message: "Pairing is not available yet"), peerName: nil)
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self, self.pairingGeneration == generation else { return }
            if self.pairingReady {
                self.startPairingOffer()
            } else {
                self.retryPairing(attempts: attempts - 1, generation: generation)
            }
        }
    }

    private func startPairingOffer() {
        guard let pairingService, let server, let assignedPort = server.port else {
            menuState = MenuState(status: .failed(message: "Pairing is not available yet"), peerName: nil)
            return
        }
        manualPairing = nil

        let result = pairingService.beginPairing(
            now: clock(),
            host: Self.localAddress() ?? "127.0.0.1",
            port: assignedPort,
            deviceName: Host.current().localizedName ?? "Graft Mac"
        )
        pairingURI = result.uri
        qrImage = QRImage.make(from: result.uri, scale: 8)
        peerAddress = "\(Self.localAddress() ?? "127.0.0.1"):\(assignedPort)"
        menuState = MenuState(status: .pairing, peerName: nil)
    }

    /// Starts a pairing window that does not need a camera and publishes the typed values.
    func beginManualPairing() {
        pairingGeneration += 1
        let generation = pairingGeneration
        guard pairingReady else {
            retryManualPairing(attempts: 10, generation: generation)
            return
        }
        startManualPairingOffer()
    }

    private func retryManualPairing(attempts: Int, generation: Int) {
        guard attempts > 0 else {
            menuState = MenuState(status: .failed(message: "Pairing is not available yet"), peerName: nil)
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self, self.pairingGeneration == generation else { return }
            if self.pairingReady {
                self.startManualPairingOffer()
            } else {
                self.retryManualPairing(attempts: attempts - 1, generation: generation)
            }
        }
    }

    private func startManualPairingOffer() {
        guard let pairingService, let server, let assignedPort = server.port else {
            menuState = MenuState(status: .failed(message: "Pairing is not available yet"), peerName: nil)
            return
        }
        pairingURI = nil
        qrImage = nil

        let pairing = pairingService.beginNumericPairing(
            now: clock(),
            host: Self.localAddress() ?? "127.0.0.1",
            port: assignedPort,
            deviceName: Host.current().localizedName ?? "Graft Mac"
        )
        manualPairing = pairing
        peerAddress = "\(pairing.host):\(pairing.port)"
        pairingURI = nil
        qrImage = nil
        menuState = MenuState(status: .pairing, peerName: nil)
    }

    /// Drops the current offer and returns the popover to its normal state.
    func cancelPairing() {
        // A retry queued at launch must not resurrect the offer after the window closed.
        pairingGeneration += 1
        pairingService?.revokePairing()
        pairingURI = nil
        qrImage = nil
        manualPairing = nil
        menuState = MenuState(status: .idle, peerName: nil)
    }

    func refreshPairingState(now: Date) {
        guard case .pairing = menuState.status else { return }
        guard let pairingService, !pairingService.isPairingOpen(now: now) else { return }

        pairingURI = nil
        qrImage = nil
        manualPairing = nil
        menuState = MenuState(status: .idle, peerName: nil)
    }

    func forgetPeer(fingerprint: String) {
        pairingService?.forget(fingerprint: fingerprint)
        try? store.remove(fingerprint: fingerprint)
        peers = store.load()
        if pendingPairedPeer?.fingerprint == fingerprint {
            pendingPairedPeer = nil
        }
        if connection?.peerFingerprint == fingerprint {
            connection?.close()
            connection = nil
        }
    }

    // MARK: - Connections

    private func attach(connection: TransportConnection) {
        guard let pairingService else { return }
        let fingerprint = connection.peerFingerprint ?? ""

        let session = ConnectionSession(
            service: pairingService,
            now: clock(),
            send: { envelope in try? connection.send(envelope) },
            close: { connection.close() }
        )
        session.peerFingerprint = fingerprint
        session.start(now: clock())

        let key = ObjectIdentifier(connection)
        sessions[key] = session
        // An unpaired peer is attached (that is what pairing is) but must not become "the"
        // connection until the handshake says it is trusted.
        if pairingService.isPaired(fingerprint) {
            self.connection = connection
        }

        let coordinator = TransferCoordinator(
            directory: receiveDirectory,
            isPaired: { [weak self] in
                guard let self else { return false }
                return self.peers.contains { $0.fingerprint == fingerprint }
            },
            sendControl: { [weak connection] envelope in try? connection?.send(envelope) },
            sendBinary: { [weak connection] data in try? connection?.sendBinary(data) },
            sendBinaryAsync: { [weak connection] data, done in
                try? connection?.sendBinary(data, completion: done)
            },
            records: TransferRecordStore(file: Self.defaultRecordStoreURL())
        )
        coordinator.onProgress = { [weak self] _ in self?.syncTransfers() }
        coordinators.insert(coordinator, for: key)

        connection.receive { [weak self] envelope in
            Task { @MainActor in
                guard let self, let session = self.sessions[key] else { return }
                session.handle(envelope: envelope, now: self.clock())

                // While a pairing window is open any certificate may complete TLS. Only a peer
                // the pairing rules actually accepted may touch the clipboard, post a banner, or
                // start a transfer.
                guard self.pairingService?.isPaired(fingerprint) == true else {
                    self.publishState(for: session)
                    return
                }

                self.connection = connection
                self.coordinators.coordinator(for: key)?.onControl(envelope)
                self.handleClipboard(envelope)
                self.handleDeviceStatus(envelope)
                if self.notifications.present(envelope) {
                    self.recentNotifications = self.notifications.recent
                }
                self.notifications.handleRemoval(envelope)
                self.syncTransfers()
                self.publishState(for: session)
                self.persistPairingIfNeeded()
            }
        }

        connection.receiveBinary { [weak self] data in
            Task { @MainActor in
                guard let self else { return }
                self.sessions[key]?.noteBinary(now: self.clock())
                self.coordinators.coordinator(for: key)?.onBinary(data)
                self.syncTransfers()
            }
        }

        if heartbeatTimer == nil {
            let timer = DispatchSource.makeTimerSource(queue: .main)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in self?.tickSessions() }
            timer.resume()
            heartbeatTimer = timer
        }
    }

    /// Drops everything tied to a closed socket.
    ///
    /// Without this the popover kept saying "Connected" until the 60 s heartbeat expired, and the
    /// session and coordinator stayed in memory for the life of the process.
    private func handleDisconnect(key: ObjectIdentifier) {
        sessions.removeValue(forKey: key)
        coordinators.coordinator(for: key)?.cancelAll()
        coordinators.remove(key)
        if let current = connection, ObjectIdentifier(current) == key {
            connection = nil
        }
        if sessions.isEmpty, case .connected = menuState.status {
            menuState = MenuState(status: .searching, peerName: nil)
            peerAddress = nil
            peerStatus = nil
            resetRingState()
        }
    }

    private func tickSessions() {
        let now = clock()
        for (key, session) in sessions {
            session.tick(now: now)
            publishState(for: session)
            if session.phase == .closing {
                sessions.removeValue(forKey: key)
                // A coordinator outliving its session would look connected but send nowhere.
                coordinators.remove(key)
            }
        }
        var stalled = false
        for coordinator in coordinators.all {
            if coordinator.checkStalls(now: now) {
                stalled = true
            }
        }
        if stalled {
            syncTransfers()
        }
        refreshPairingState(now: now)
        pollClipboard(now: now)
        pollScreenshots()
    }

    private func publishState(for session: ConnectionSession) {
        switch session.phase {
        case .ready:
            let name = peers.first { $0.fingerprint == session.peerFingerprint }?.name ?? "your phone"
            if session.peerFingerprint?.isEmpty == false, menuState.status != .connected(peer: name) {
                clipboard.syncChangeCount()
                if autoSendScreenshots {
                    screenshotWatcher.baseline()
                }
                menuState = MenuState(status: .connected(peer: name), peerName: name)
            }
        case .disconnected:
            if case .connected = menuState.status {
                menuState = MenuState(status: .searching, peerName: nil)
            }
        default:
            break
        }
    }

    /// Records the peer the moment pairing succeeds, so a restart remembers it.
    private func persistPairingIfNeeded() {
        guard let pairingService, let accepted = pairingService.lastPairedPeer else { return }
        pendingPairedPeer = accepted
        guard !peers.contains(where: { $0.fingerprint == accepted.fingerprint }) else { return }

        let peer = PairedPeer(
            fingerprint: accepted.fingerprint,
            name: accepted.name,
            platform: "android",
            pairedAtMillis: Int64(clock().timeIntervalSince1970 * 1000)
        )
        try? store.add(peer)
        peers = store.load()
        pairingURI = nil
        qrImage = nil
    }

    // MARK: - Device status & ring

    private func handleDeviceStatus(_ envelope: Envelope) {
        if let status = PeerDeviceStatus.parse(envelope) {
            peerStatus = status
        }
    }

    /// Starts or stops ringing the connected phone to help locate it.
    func toggleRingPeer() {
        guard let connection, menuState.isConnected else { return }
        let start = !isRingingPeer
        let envelope = Envelope(
            type: "device.ring",
            payload: .object(["action": .string(start ? "start" : "stop")])
        )
        try? connection.send(envelope)
        ringResetTask?.cancel()
        ringResetTask = nil
        isRingingPeer = start
        if start {
            let work = DispatchWorkItem { [weak self] in
                Task { @MainActor in
                    self?.isRingingPeer = false
                    self?.ringResetTask = nil
                }
            }
            ringResetTask = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
        }
    }

    private func resetRingState() {
        ringResetTask?.cancel()
        ringResetTask = nil
        isRingingPeer = false
    }

    /// Sends an inline reply typed on a macOS notification banner back to the Android app.
    func sendNotificationReply(key: String, text: String) {
        guard let connection, menuState.isConnected, !key.isEmpty, !text.isEmpty else { return }
        let envelope = Envelope(
            type: "notification.reply",
            payload: .object([
                "key": .string(key),
                "text": .string(NotificationPresenter.truncate(text, maxBytes: NotificationPresenter.maxBodyBytes))
            ])
        )
        try? connection.send(envelope)
    }

    /// Tells the Android phone to dismiss the matching notification.
    func sendNotificationDismiss(key: String) {
        guard let connection, menuState.isConnected, !key.isEmpty else { return }
        let envelope = Envelope(
            type: "notification.dismiss",
            payload: .object(["key": .string(key)])
        )
        try? connection.send(envelope)
    }

    // MARK: - Clipboard

    private func handleClipboard(_ envelope: Envelope) {
        guard autoCopyClipboard else { return }
        clipboard.received(envelope)
    }

    /// Sends the current pasteboard text to the connected phone.
    /// Toggles whether notification bodies are shown in the banner.
    func updateNotificationPreview(enabled: Bool) {
        notifications.showPreviews = enabled
        notificationPreviews = enabled
    }

    func sendClipboard() {
        if let envelope = clipboard.outgoing() {
            try? connection?.send(envelope)
            clipboard.syncChangeCount()
            lastSharedClipboard = envelope.payload.objectValue?["body"]?.stringValue
            return
        }
        if let pngData = clipboard.outgoingImagePNG(), !pngData.isEmpty {
            clipboard.syncChangeCount()
            _ = sendTempImagePNG(pngData)
            return
        }
        transferFailure = clipboard.lastError ?? "There is no text or image on the clipboard"
    }

    @discardableResult
    private func sendTempImagePNG(_ data: Data) -> Bool {
        guard let connection,
              let coordinator = coordinators.coordinator(for: ObjectIdentifier(connection)) ?? coordinators.all.first else {
            transferFailure = "Connect a phone first"
            return false
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let container = FileManager.default.temporaryDirectory
            .appendingPathComponent("graft-clip-\(UUID().uuidString)", isDirectory: true)
        let fileURL = container.appendingPathComponent("Clipboard-\(formatter.string(from: clock())).png")
        do {
            try FileManager.default.createDirectory(at: container, withIntermediateDirectories: true)
            try data.write(to: fileURL)
        } catch {
            try? FileManager.default.removeItem(at: container)
            transferFailure = "Could not prepare clipboard image"
            return false
        }

        let id = nextTransferId
        nextTransferId += 1
        outgoingTempArchives[id] = container
        do {
            try coordinator.offer(url: fileURL, transferId: id)
            syncTransfers()
            return true
        } catch {
            cleanupTempArchive(for: id)
            transferFailure = "Could not send clipboard image"
            return false
        }
    }

    private func pollClipboard(now: Date) {
        guard autoShareClipboard, menuState.isConnected, let connection else { return }
        guard let envelope = clipboard.pollOutgoing(now: now) else { return }
        try? connection.send(envelope)
        lastSharedClipboard = envelope.payload.objectValue?["body"]?.stringValue
    }

    private func pollScreenshots() {
        guard autoSendScreenshots, menuState.isConnected, connection != nil else { return }
        let screenshots = screenshotWatcher.pollNewScreenshots()
        guard !screenshots.isEmpty else { return }
        sendFiles(urls: screenshots)
    }

    // MARK: - File transfer

    @discardableResult
    func sendFile(url: URL) -> Bool {
        guard let connection,
              let coordinator = coordinators.coordinator(for: ObjectIdentifier(connection)) ?? coordinators.all.first else {
            transferFailure = "Connect a phone first"
            return false
        }
        let id = nextTransferId
        nextTransferId += 1

        let sendURL: URL
        var tempArchiveDir: URL?
        let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
        if isDirectory {
            guard let zipped = Self.zipDirectory(url) else {
                transferFailure = "Could not compress folder \(url.lastPathComponent)"
                return false
            }
            sendURL = zipped.archive
            tempArchiveDir = zipped.container
        } else {
            sendURL = url
        }

        do {
            if let tempArchiveDir {
                outgoingTempArchives[id] = tempArchiveDir
            }
            try coordinator.offer(url: sendURL, transferId: id)
            syncTransfers()
            return true
        } catch {
            if let tempArchiveDir {
                outgoingTempArchives.removeValue(forKey: id)
                try? FileManager.default.removeItem(at: tempArchiveDir)
            }
            transferFailure = "Could not read \(url.lastPathComponent)"
            return false
        }
    }

    func sendFiles(urls: [URL]) {
        for url in urls {
            _ = sendFile(url: url)
        }
    }

    func cancelTransfer(id: UInt32) {
        for coordinator in coordinators.all {
            coordinator.cancel(transferId: id)
        }
        cleanupTempArchive(for: id)
        syncTransfers()
    }

    /// Zips a folder into a temporary directory using macOS's native file coordinator.
    nonisolated static func zipDirectory(_ directory: URL) -> (archive: URL, container: URL)? {
        let container = FileManager.default.temporaryDirectory
            .appendingPathComponent("graft-zip-\(UUID().uuidString)", isDirectory: true)
        let target = container.appendingPathComponent("\(directory.lastPathComponent).zip")
        var coordError: NSError?
        var copied = false

        let fileCoordinator = NSFileCoordinator()
        fileCoordinator.coordinate(readingItemAt: directory, options: .forUploading, error: &coordError) { zipURL in
            do {
                try FileManager.default.createDirectory(at: container, withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: zipURL, to: target)
                copied = true
            } catch {
                copied = false
            }
        }
        guard coordError == nil, copied else {
            try? FileManager.default.removeItem(at: container)
            return nil
        }
        return (archive: target, container: container)
    }

    private func cleanupTempArchive(for id: UInt32) {
        if let container = outgoingTempArchives.removeValue(forKey: id) {
            try? FileManager.default.removeItem(at: container)
        }
    }

    private func cleanupAllTempArchives() {
        for container in outgoingTempArchives.values {
            try? FileManager.default.removeItem(at: container)
        }
        outgoingTempArchives.removeAll()
    }

    private func syncTransfers() {
        var merged: [UInt32: TransferProgress] = [:]
        var failureMessage: String?

        for coordinator in coordinators.all {
            recordFinishedTransfers(coordinator)
            let finished = coordinator.completed.union(coordinator.failures.keys)
            merged.merge(coordinator.progress.filter { !finished.contains($0.key) }) { current, _ in current }
            if let failure = coordinator.failures.values.first {
                failureMessage = Self.describe(failure)
            }
        }

        transfers = merged
        transferMetrics = speedTracker.update(transfers: merged, now: clock())
        // A success after a failure clears the banner; an error only stands while it is the latest
        // outcome and nothing is in flight.
        if lastTransferSucceeded || !merged.isEmpty {
            failureMessage = nil
        }
        transferFailure = failureMessage
        transferHistory = history.records
    }

    /// Records each transfer once per outcome, so a failed retry that later commits updates its row.
    private func recordFinishedTransfers(_ coordinator: TransferCoordinator) {
        var changed = false
        for id in coordinator.completed.union(coordinator.failures.keys) {
            let failure = coordinator.failures[id]
            let progress = coordinator.progress[id]
            let name = progress?.name ?? "File"
            let key = "\(id)|\(name)|\(progress?.direction.rawValue ?? "receive")"
            if recordedOutcomes.keys.contains(key), recordedOutcomes[key]! == failure { continue }
            recordedOutcomes[key] = .some(failure)
            lastTransferSucceeded = failure == nil
            cleanupTempArchive(for: id)

            history.record(
                id: id,
                name: name,
                bytes: progress?.total ?? 0,
                direction: progress?.direction ?? .receive,
                failure: failure,
                destinationPath: coordinator.destinations[id]?.path
            )
            changed = true
        }
        if changed {
            historyStore.save(history.records)
        }
    }

    private static func describe(_ failure: TransferFailure) -> String {
        switch failure {
        case .unpairedPeer: return "The phone refused the file"
        case .duplicateTransfer: return "That transfer is already running"
        case .unsafeName: return "The file name is not safe to save"
        case .wrongOffset: return "The transfer got out of sync"
        case .hashMismatch: return "The file did not arrive intact"
        case .diskFull: return "There is not enough space for this file"
        case .stalled: return "The transfer timed out"
        }
    }

    static func defaultRecordStoreURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        return base
            .appendingPathComponent("Graft", isDirectory: true)
            .appendingPathComponent("transfers.json")
    }

    static func defaultReceiveDirectory() -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Downloads/Graft", isDirectory: true)
    }

    // MARK: - Paths

    static func defaultStoreURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        return base
            .appendingPathComponent("Graft", isDirectory: true)
            .appendingPathComponent("peers.json")
    }

    /// Best-effort local IPv4 address for the pairing URI. Prefers physical LAN interfaces (`en0`,
    /// `en*`) over VPN/Docker/virtual adapters (`utun*`, `bridge*`, `awdl*`).
    static func localAddress() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }

        var candidates: [(name: String, ip: String)] = []
        var pointer = first
        while true {
            let flags = Int32(pointer.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            if isUp, !isLoopback, let addr = pointer.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let result = getnameinfo(
                    addr,
                    socklen_t(addr.pointee.sa_len),
                    &host,
                    socklen_t(host.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
                if result == 0 {
                    let name = String(cString: pointer.pointee.ifa_name)
                    let ip = String(cString: host)
                    candidates.append((name: name, ip: ip))
                }
            }
            guard let next = pointer.pointee.ifa_next else { break }
            pointer = next
        }
        return selectLocalAddress(from: candidates)
    }

    /// Selects the most likely LAN IPv4 address from active interface candidates.
    nonisolated static func selectLocalAddress(from candidates: [(name: String, ip: String)]) -> String? {
        let usable = candidates.filter { !$0.ip.hasPrefix("169.254.") && !$0.ip.hasPrefix("127.") }
        if let en0 = usable.first(where: { $0.name == "en0" }) {
            return en0.ip
        }
        if let en = usable.first(where: { $0.name.hasPrefix("en") }) {
            return en.ip
        }
        let virtualPrefixes = ["utun", "awdl", "llw", "bridge", "vmenet", "feth", "anpi", "ap", "lo", "gif", "stf"]
        if let physical = usable.first(where: { candidate in
            !virtualPrefixes.contains { candidate.name.hasPrefix($0) }
        }) {
            return physical.ip
        }
        return usable.first?.ip
    }
}
