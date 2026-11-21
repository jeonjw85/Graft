import Foundation
import GraftProtocol
import Network
import Security

/// A mutually authenticated TLS WebSocket server.
///
/// TLS 1.3 with a self-signed identity; peers are authorized by SPKI fingerprint inside the TLS
/// verify block, so unauthorized peers never complete a handshake.
public final class TransportServer: @unchecked Sendable {
    private let identity: DeviceIdentity
    private let authorizer: PeerAuthorizer
    private let requestedPort: UInt16
    private let queue = DispatchQueue(label: "kr.jjw.graft.server")
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: TransportConnection] = [:]
    private let queueKey = DispatchSpecificKey<Void>()
    public private(set) var port: UInt16?

    public var onConnection: ((TransportConnection) -> Void)?
    /// Fired when an accepted connection closes, so the service can drop its session immediately.
    public var onDisconnect: ((ObjectIdentifier) -> Void)?

    public init(identity: DeviceIdentity, authorizer: PeerAuthorizer, port: UInt16 = 47800) {
        self.identity = identity
        self.authorizer = authorizer
        self.requestedPort = port
    }

    public func start() throws {
        queue.setSpecific(key: queueKey, value: ())
        let tlsOptions = NWProtocolTLS.Options()
        let securityOptions = tlsOptions.securityProtocolOptions
        guard let secIdentity = sec_identity_create(identity.identity) else {
            throw TransportError.identityUnavailable
        }
        sec_protocol_options_set_local_identity(securityOptions, secIdentity)
        sec_protocol_options_set_peer_authentication_required(securityOptions, true)

        let authorizer = self.authorizer
        sec_protocol_options_set_verify_block(securityOptions, { _, peerTrust, complete in
            let trust = sec_trust_copy_ref(peerTrust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] ?? []
            guard let leaf = chain.first,
                  let fingerprint = try? SpkiFingerprint.base64URL(certificate: leaf) else {
                complete(false)
                return
            }
            complete(authorizer.isAuthorized(fingerprint))
        }, queue)

        let webSocketOptions = NWProtocolWebSocket.Options()
        webSocketOptions.autoReplyPing = true

        let parameters = NWParameters(tls: tlsOptions)
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocketOptions, at: 0)
        parameters.allowLocalEndpointReuse = true

        // NWEndpoint.Port.any reports port 0 and never binds, so pick a concrete caller-supplied
        // port. Tests allocate a free port themselves.
        guard let port = NWEndpoint.Port(rawValue: requestedPort), requestedPort != 0 else {
            throw TransportError.listenerFailed
        }
        let listener = try NWListener(using: parameters, on: port)
        listener.stateUpdateHandler = { state in
            if case .failed = state {
                self.listener?.cancel()
            }
        }
        listener.newConnectionHandler = { [weak self] connection in

            guard let self else {
                connection.cancel()
                return
            }
            let transport = TransportConnection(connection: connection, queue: self.queue)
            transport.onClose = { [weak self] key in
                self?.connections.removeValue(forKey: key)
                self?.onDisconnect?(key)
            }
            // Fire only after the TLS handshake and fingerprint check succeed; newConnectionHandler
            // runs before authentication, so reporting there would announce rejected peers.
            transport.onReady = { [weak self] in
                self?.onConnection?(transport)
            }
            self.connections[ObjectIdentifier(transport)] = transport
            transport.start()
        }
        listener.start(queue: queue)
        self.listener = listener

        // NWListener reports its port asynchronously; block briefly so callers can connect.
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if let assigned = listener.port, case .ready = listener.state {
                self.port = assigned.rawValue
                return
            }
            if case .failed = listener.state {
                throw TransportError.listenerFailed
            }
            Thread.sleep(forTimeInterval: 0.02)
        }
        throw TransportError.listenerTimedOut
    }

    public func stop() {
        // connections is owned by self.queue, so teardown must run there. Callbacks also run on
        // that queue; dispatch_sync from inside one aborts the process, so detect reentrancy.
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            teardown()
        } else {
            queue.sync { teardown() }
        }
    }

    private func teardown() {
        listener?.cancel()
        listener = nil
        for connection in connections.values {
            connection.close()
        }
        connections.removeAll()
    }
}

public enum TransportError: Error {
    case identityUnavailable
    case listenerFailed
    case listenerTimedOut
    case notReady
    case encodingFailed
    case connectionFailed(String)
}

/// One accepted WebSocket connection on the server side.
public final class TransportConnection: @unchecked Sendable {
    private let connection: NWConnection
    private let queue: DispatchQueue
    private var isReady = false
    private var handlers: [(Envelope) -> Void] = []
    private var binaryHandlers: [(Data) -> Void] = []
    private let sendLock = NSLock()
    private let handlerLock = NSLock()
    private var pendingEnvelopes: [Envelope] = []
    private var pendingBinary: [Data] = []
    private static let maxPendingFrames = 32
    private var pendingSends = 0
    private var closeWhenDrained = false

    var onClose: ((ObjectIdentifier) -> Void)?
    var onReady: (() -> Void)?

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                guard !self.isReady else { return }
                self.isReady = true
                self.peerFingerprint = Self.peerFingerprint(of: self.connection)
                self.onReady?()
            case .failed:
                self.onClose?(ObjectIdentifier(self))
            case .cancelled:
                self.onClose?(ObjectIdentifier(self))
            default:
                break
            }
        }
        connection.start(queue: queue)
        readLoop()
    }

    public func send(_ envelope: Envelope) throws {
        try send(envelope, completion: nil)
    }

    /// Sends a binary WebSocket frame (file data).
    public func sendBinary(_ data: Data, completion: (() -> Void)? = nil) throws {
        guard isReady else { throw TransportError.notReady }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        beganSend()
        connection.send(
            content: data,
            contentContext: context,
            isComplete: true,
            completion: .contentProcessed { _ in
                completion?()
                self.endedSend()
            }
        )
    }

    /// Sends a frame and calls `completion` after it is handed to the network stack.
    ///
    /// Callers that close right after sending must use this: cancelling immediately can drop the
    /// frame, so the peer never sees the error it was sent.
    public func send(_ envelope: Envelope, completion: (() -> Void)?) throws {
        guard isReady else { throw TransportError.notReady }
        let data = try MessageCodec.encode(envelope)
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        beganSend()
        connection.send(
            content: data,
            contentContext: context,
            isComplete: true,
            completion: .contentProcessed { _ in
                completion?()
                self.endedSend()
            }
        )
    }

    public func receive(_ handler: @escaping (Envelope) -> Void) {
        handlerLock.lock()
        handlers.append(handler)
        let buffered = pendingEnvelopes
        pendingEnvelopes.removeAll()
        handlerLock.unlock()
        // A peer can answer before the service has finished registering (registration hops through
        // the main actor). Replaying what arrived early is what keeps the first hello from vanishing.
        for envelope in buffered { handler(envelope) }
    }

    /// Registers a handler for binary frames (file data).
    public func receiveBinary(_ handler: @escaping (Data) -> Void) {
        handlerLock.lock()
        binaryHandlers.append(handler)
        let buffered = pendingBinary
        pendingBinary.removeAll()
        handlerLock.unlock()
        for data in buffered { handler(data) }
    }

    /// The authenticated peer's SPKI fingerprint, recorded during the TLS handshake.
    public internal(set) var peerFingerprint: String?

    /// Closes once everything already queued has been handed to the network stack.
    ///
    /// An error reply sent immediately before a cancel is dropped, so the peer only sees a bare
    /// close and cannot tell "you must pair" from "the network died".
    public func close() {
        sendLock.lock()
        if pendingSends == 0 {
            sendLock.unlock()
            connection.cancel()
            return
        }
        closeWhenDrained = true
        sendLock.unlock()
    }

    private func beganSend() {
        sendLock.lock()
        pendingSends += 1
        sendLock.unlock()
    }

    private func endedSend() {
        sendLock.lock()
        pendingSends -= 1
        let closeNow = closeWhenDrained && pendingSends == 0
        sendLock.unlock()
        if closeNow {
            connection.cancel()
        }
    }

    /// Reads the authenticated peer's SPKI fingerprint from this connection's TLS metadata.
    ///
    /// `newConnectionHandler` runs before the handshake, so attribution must happen here, keyed by
    /// the connection itself. The verify block only proves the peer is allowed; it cannot tell the
    /// protocol layer which peer it was.
    private static func data(from source: dispatch_data_t) -> Data {
        var data = Data()
        (source as DispatchData).enumerateBytes { buffer, _, _ in
            data.append(contentsOf: buffer)
        }
        return data
    }

    static func peerFingerprint(of connection: NWConnection) -> String? {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition)
            as? NWProtocolTLS.Metadata else {
            return nil
        }
        guard let peerKey = sec_protocol_metadata_copy_peer_public_key(
            metadata.securityProtocolMetadata
        ) else {
            return nil
        }
        let keyData = Self.data(from: peerKey)
        // Security returns the certificate's SubjectPublicKeyInfo here (91 bytes for P-256).
        if keyData.count == 91 {
            return SpkiFingerprint.base64URL(spki: keyData)
        }
        return try? SpkiFingerprint.base64URL(publicKeyPoint: keyData)
    }

    private func deliver(_ envelope: Envelope) {
        handlerLock.lock()
        let targets = handlers
        if targets.isEmpty, pendingEnvelopes.count < Self.maxPendingFrames {
            pendingEnvelopes.append(envelope)
        }
        handlerLock.unlock()
        for handler in targets { handler(envelope) }
    }

    private func deliverBinary(_ data: Data) {
        handlerLock.lock()
        let targets = binaryHandlers
        if targets.isEmpty, pendingBinary.count < Self.maxPendingFrames {
            pendingBinary.append(data)
        }
        handlerLock.unlock()
        for handler in targets { handler(data) }
    }

    private func readLoop() {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                    as? NWProtocolWebSocket.Metadata
                if metadata?.opcode == .binary {
                    self.deliverBinary(data)
                } else if let envelope = try? MessageCodec.decode(data) {
                    self.deliver(envelope)
                }
            }
            if error != nil { return }
            self.readLoop()
        }
    }
}
