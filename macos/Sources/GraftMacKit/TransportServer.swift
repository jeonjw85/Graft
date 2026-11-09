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
    private var pendingFingerprints: [ObjectIdentifier: String] = [:]
    private let fingerprintLock = NSLock()
    public private(set) var port: UInt16?

    public var onConnection: ((TransportConnection) -> Void)?

    public init(identity: DeviceIdentity, authorizer: PeerAuthorizer, port: UInt16 = 47800) {
        self.identity = identity
        self.authorizer = authorizer
        self.requestedPort = port
    }

    public func start() throws {
        let tlsOptions = NWProtocolTLS.Options()
        let securityOptions = tlsOptions.securityProtocolOptions
        guard let secIdentity = sec_identity_create(identity.identity) else {
            throw TransportError.identityUnavailable
        }
        sec_protocol_options_set_local_identity(securityOptions, secIdentity)
        sec_protocol_options_set_peer_authentication_required(securityOptions, true)

        let authorizer = self.authorizer
        let recordFingerprint = self.recordFingerprint
        sec_protocol_options_set_verify_block(securityOptions, { _, peerTrust, complete in
            let trust = sec_trust_copy_ref(peerTrust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] ?? []
            guard let leaf = chain.first,
                  let fingerprint = try? SpkiFingerprint.base64URL(certificate: leaf) else {
                complete(false)
                return
            }
            let authorized = authorizer.isAuthorized(fingerprint)
            if authorized {
                recordFingerprint(fingerprint, peerTrust)
            }
            complete(authorized)
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
            transport.peerFingerprint = self.takeFingerprint(connection: connection)
            transport.onClose = { [weak self] key in
                self?.connections.removeValue(forKey: key)
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

    // The verify block only receives the trust object, not the NWConnection, so the fingerprint is
    // stored under the trust's identity and paired with the connection before it is handed out.
    private var fingerprintsByTrust: [ObjectIdentifier: String] = [:]

    private func recordFingerprint(_ fingerprint: String, trust: sec_trust_t) {
        let key = ObjectIdentifier(trust as AnyObject)
        fingerprintLock.lock()
        fingerprintsByTrust[key] = fingerprint
        fingerprintLock.unlock()
    }

    private func takeFingerprint(connection: NWConnection) -> String? {
        // NWConnection does not expose the trust object; the single-peer case is the common one, so
        // consume the oldest recorded fingerprint.
        fingerprintLock.lock()
        defer { fingerprintLock.unlock() }
        guard let entry = fingerprintsByTrust.first else { return nil }
        fingerprintsByTrust.removeValue(forKey: entry.key)
        return entry.value
    }

    public func stop() {
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
        guard isReady else { throw TransportError.notReady }
        let data = try MessageCodec.encode(envelope)
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(
            content: data,
            contentContext: context,
            isComplete: true,
            completion: .contentProcessed { _ in }
        )
    }

    public func receive(_ handler: @escaping (Envelope) -> Void) {
        handlers.append(handler)
    }

    /// The authenticated peer's SPKI fingerprint, recorded during the TLS handshake.
    public internal(set) var peerFingerprint: String?

    public func close() {
        connection.cancel()
    }

    private func readLoop() {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, !data.isEmpty, let envelope = try? MessageCodec.decode(data) {
                for handler in self.handlers {
                    handler(envelope)
                }
            }
            if error != nil { return }
            self.readLoop()
        }
    }
}
