import Foundation
import GraftProtocol
import Network
import Security

/// A client connection used by the macOS tests and the headless harness.
///
/// Android uses OkHttp; this exists so the Mac side can be exercised without a phone. It is built
/// on `URLSessionWebSocketTask`, which handles the HTTP upgrade and TLS client certificate
/// challenge that `NWConnection` does not perform on its own.
public final class TransportPeer: NSObject, @unchecked Sendable {
    private let identity: DeviceIdentity
    private let expectedServerFingerprint: String
    private let url: URL
    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var handlers: [(Envelope) -> Void] = []

    public var onFailure: (() -> Void)?

    public init(
        identity: DeviceIdentity,
        host: String,
        port: UInt16,
        expectedServerFingerprint: String
    ) throws {
        self.identity = identity
        self.expectedServerFingerprint = expectedServerFingerprint
        guard let url = URL(string: "wss://\(host):\(port)/graft") else {
            throw TransportError.connectionFailed("invalid url")
        }
        self.url = url
    }

    /// Connects and calls `completion` once the WebSocket is open.
    public func start(completion: @escaping @Sendable (Result<Void, Error>) -> Void) {
        let configuration = URLSessionConfiguration.ephemeral
        let session = URLSession(
            configuration: configuration,
            delegate: self,
            delegateQueue: nil
        )
        self.session = session

        let task = session.webSocketTask(with: url)
        self.task = task
        task.resume()
        receiveLoop()

        // URLSessionWebSocketTask has no "ready" callback; the first send is what surfaces a
        // handshake failure, so probe with a padding frame the server ignores.
        task.send(.string("")) { error in
            if let error {
                completion(.failure(TransportError.connectionFailed(String(describing: error))))
            } else {
                completion(.success(()))
            }
        }
    }

    public func send(_ envelope: Envelope) throws {
        guard let task else { throw TransportError.notReady }
        let data = try MessageCodec.encode(envelope)
        guard let text = String(data: data, encoding: .utf8) else {
            throw TransportError.encodingFailed
        }
        task.send(.string(text)) { _ in }
    }

    public func receive(_ handler: @escaping (Envelope) -> Void) {
        handlers.append(handler)
    }

    public func close() {
        task?.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel()
        task = nil
        session = nil
    }

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message,
                   let envelope = try? MessageCodec.decode(Data(text.utf8)) {
                    for handler in self.handlers {
                        handler(envelope)
                    }
                }
                self.receiveLoop()
            case .failure:
                self.onFailure?()
            }
        }
    }
}

extension TransportPeer: URLSessionDelegate {
    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodClientCertificate:
            completionHandler(
                .useCredential,
                URLCredential(identity: identity.identity, certificates: [identity.certificate], persistence: .none)
            )

        case NSURLAuthenticationMethodServerTrust:
            guard let trust = challenge.protectionSpace.serverTrust,
                  let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let leaf = chain.first,
                  let fingerprint = try? SpkiFingerprint.base64URL(certificate: leaf),
                  fingerprint == expectedServerFingerprint else {
                completionHandler(.cancelAuthenticationChallenge, nil)
                return
            }
            completionHandler(.useCredential, URLCredential(trust: trust))

        default:
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
