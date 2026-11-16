import AppKit
import SwiftUI
import Foundation
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
    @Published private(set) var transfers: [UInt32: TransferProgress] = [:]
    @Published private(set) var transferFailure: String?
    @Published private(set) var receiveDirectory: URL = GraftService.defaultReceiveDirectory()
    @Published var autoCopyClipboard = true
    @Published private(set) var lastSharedClipboard: String?

    private let port: UInt16
    private let store: PeerStore
    private let clock: () -> Date
    private let identity: DeviceIdentity?
    private let pairingService: PairingService?
    private var server: TransportServer?
    private var advertiser: BonjourAdvertiser?
    private var sessions: [ObjectIdentifier: ConnectionSession] = [:]
    private var coordinators: [ObjectIdentifier: TransferCoordinator] = [:]
    private var nextTransferId: UInt32 = 1
    private let clipboard = ClipboardBridge(writer: PasteboardWriter(), reader: PasteboardReader())

    /// The most recent live connection, used for menu actions such as "Send clipboard".
    private var connection: TransportConnection?
    private var heartbeatTimer: DispatchSourceTimer?
    private(set) var lastError: Error?
    private(set) var hasStarted = false

    /// The peer accepted during this session, if any. Cleared when the user forgets a device so a
    /// later frame cannot silently re-add it.
    private(set) var pendingPairedPeer: (fingerprint: String, name: String)?

    init(port: UInt16 = 47800, storeURL: URL? = nil, clock: @escaping () -> Date = Date.init) {
        self.port = port
        self.clock = clock
        self.store = PeerStore(url: storeURL ?? Self.defaultStoreURL())

        do {
            let identity = try DeviceIdentity.loadOrCreate(tag: "kr.jjw.graft.mac")
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
        switch menuState.status {
        case .connected: return .green
        case .failed: return .red
        case .pairing, .searching: return .orange
        case .idle: return .secondary
        }
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

        // Advertise so a phone that already paired can reconnect without scanning again.
        let discoverable = BonjourAdvertiser(
            port: assignedPort,
            fingerprint: identity.fingerprint,
            deviceName: Host.current().localizedName ?? "Graft Mac"
        )
        try? discoverable.start()
        advertiser = discoverable
    }

    func stop() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        for session in sessions.values {
            session.tick(now: clock())
        }
        sessions.removeAll()
        advertiser?.stop()
        advertiser = nil
        server?.stop()
        server = nil
    }

    func markStarted() {
        hasStarted = true
    }

    func reloadPeers() {
        peers = store.load()
    }

    // MARK: - Pairing

    func beginPairing() {
        guard let pairingService, let server, let assignedPort = server.port else { return }

        let result = pairingService.beginPairing(
            now: clock(),
            host: Self.localAddress() ?? "127.0.0.1",
            port: assignedPort,
            deviceName: Host.current().localizedName ?? "Graft Mac"
        )
        pairingURI = result.uri
        qrImage = QRImage.make(from: result.uri, scale: 8)
        menuState = MenuState(status: .pairing, peerName: nil)
    }

    func refreshPairingState(now: Date) {
        guard case .pairing = menuState.status else { return }
        guard let pairingService, !pairingService.isPairingOpen(now: now) else { return }

        pairingURI = nil
        qrImage = nil
        menuState = MenuState(status: .idle, peerName: nil)
    }

    func forgetPeer(fingerprint: String) {
        try? store.remove(fingerprint: fingerprint)
        peers = store.load()
        if pendingPairedPeer?.fingerprint == fingerprint {
            pendingPairedPeer = nil
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
        self.connection = connection

        let coordinator = TransferCoordinator(
            directory: receiveDirectory,
            isPaired: { [weak self] in
                guard let self else { return false }
                return self.peers.contains { $0.fingerprint == fingerprint }
            },
            sendControl: { [weak connection] envelope in try? connection?.send(envelope) },
            sendBinary: { [weak connection] data in try? connection?.sendBinary(data) },
            records: TransferRecordStore(file: Self.defaultRecordStoreURL())
        )
        coordinators[key] = coordinator

        connection.receive { [weak self] envelope in
            Task { @MainActor in
                guard let self, let session = self.sessions[key] else { return }
                session.handle(envelope: envelope, now: self.clock())
                self.coordinators[key]?.onControl(envelope)
                self.handleClipboard(envelope)
                self.syncTransfers()
                self.publishState(for: session)
                self.persistPairingIfNeeded()
            }
        }

        connection.receiveBinary { [weak self] data in
            Task { @MainActor in
                guard let self else { return }
                self.coordinators[key]?.onBinary(data)
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

    private func tickSessions() {
        let now = clock()
        for (key, session) in sessions {
            session.tick(now: now)
            publishState(for: session)
            if session.phase == .closing {
                sessions.removeValue(forKey: key)
            }
        }
        refreshPairingState(now: now)
    }

    private func publishState(for session: ConnectionSession) {
        switch session.phase {
        case .ready:
            let name = peers.first { $0.fingerprint == session.peerFingerprint }?.name ?? "your phone"
            if session.peerFingerprint?.isEmpty == false, menuState.status != .connected(peer: name) {
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

    // MARK: - Clipboard

    private func handleClipboard(_ envelope: Envelope) {
        guard autoCopyClipboard else { return }
        clipboard.received(envelope)
    }

    /// Sends the current pasteboard text to the connected phone.
    func sendClipboard() {
        guard let envelope = clipboard.outgoing() else {
            transferFailure = "There is no text on the clipboard"
            return
        }
        try? connection?.send(envelope)
        lastSharedClipboard = envelope.payload.objectValue?["body"]?.stringValue
    }

    // MARK: - File transfer

    @discardableResult
    func sendFile(url: URL) -> Bool {
        guard let coordinator = coordinators.values.first else {
            transferFailure = "Connect a phone first"
            return false
        }
        let id = nextTransferId
        nextTransferId += 1
        do {
            try coordinator.offer(url: url, transferId: id)
            syncTransfers()
            return true
        } catch {
            transferFailure = "Could not read \(url.lastPathComponent)"
            return false
        }
    }

    private func syncTransfers() {
        var merged: [UInt32: TransferProgress] = [:]
        for coordinator in coordinators.values {
            merged.merge(coordinator.progress) { current, _ in current }
            if let failure = coordinator.failures.values.first {
                transferFailure = Self.describe(failure)
            }
        }
        transfers = merged
    }

    private static func describe(_ failure: TransferFailure) -> String {
        switch failure {
        case .unpairedPeer: return "The phone refused the file"
        case .duplicateTransfer: return "That transfer is already running"
        case .unsafeName: return "The file name is not safe to save"
        case .wrongOffset: return "The transfer got out of sync"
        case .hashMismatch: return "The file did not arrive intact"
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

    /// Best-effort local IPv4 address for the pairing URI. The Bonjour path resolves the rest.
    static func localAddress() -> String? {
        var address: String?
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }

        var pointer = first
        while true {
            let flags = Int32(pointer.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            if isUp, !isLoopback, pointer.pointee.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let result = getnameinfo(
                    pointer.pointee.ifa_addr,
                    socklen_t(pointer.pointee.ifa_addr.pointee.sa_len),
                    &host,
                    socklen_t(host.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
                if result == 0 {
                    address = String(cString: host)
                }
            }
            guard let next = pointer.pointee.ifa_next else { break }
            pointer = next
        }
        return address
    }
}
