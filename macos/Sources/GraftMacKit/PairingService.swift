import Foundation
import GraftProtocol

/// Applies the protocol rules for an incoming connection and produces replies.
///
/// Kept separate from `TransportServer` so pairing and "unpaired connections may only pair" can be
/// tested without sockets.
public final class PairingService: @unchecked Sendable {
    private enum Key {
        static let pairingOpen = "pairingOpen"
        static let paired = "paired"
    }

    private var tokens: PairingTokenStore
    private let lock = NSLock()
    private var pairedFingerprints: Set<String>
    private let local: DeviceIdentity

    /// Fingerprints the server will accept at the TLS layer.
    public var allowedFingerprints: Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return pairedFingerprints
    }

    public init(local: DeviceIdentity, paired: Set<String> = [], tokens: PairingTokenStore = PairingTokenStore()) {
        self.local = local
        self.pairedFingerprints = paired
        self.tokens = tokens
    }

    /// True while an unused, unexpired token exists. The TLS layer admits unknown peers only then.
    public func isPairingOpen(now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return tokens.isActive(now: now)
    }

    /// Issues a pairing token and returns the URI to display as a QR code.
    public func beginPairing(
        now: Date,
        host: String,
        port: UInt16,
        deviceName: String
    ) -> (uri: String, token: String) {
        lock.lock()
        let token = tokens.issue(now: now)
        lock.unlock()

        let uri = PairingURI.build(
            host: host,
            port: port,
            fingerprint: local.fingerprint,
            token: token.value,
            name: deviceName
        )
        return (uri, token.value)
    }

    /// Handles one decoded control frame and returns the replies to send plus whether to close.
    public func handle(
        envelope: Envelope,
        peerFingerprint: String,
        now: Date
    ) -> (replies: [Envelope], close: Bool) {
        // Only pair.request is legal before pairing. Everything else is refused and the connection
        // is closed, otherwise an unpaired peer could learn the device identity and capabilities.
        guard isPaired(peerFingerprint) else {
            guard envelope.type == "pair.request" else {
                return ([errorReply(to: envelope, code: "unpaired", message: "pairing required")], true)
            }
            return handlePairRequest(envelope, peerFingerprint: peerFingerprint, now: now)
        }

        switch envelope.type {
        case "pair.request":
            // Replaying the pairing exchange on an established connection is never legitimate.
            return ([errorReply(to: envelope, code: "unpaired", message: "already paired")], true)

        case "hello":
            return ([helloOk(replyTo: envelope)], false)

        case "ping":
            return ([Envelope(re: envelope.id, type: "pong", payload: .object([:]))], false)

        default:
            // A paired peer may send types we do not understand; ignoring is the compatibility rule.
            return ([], false)
        }
    }

    private func isPaired(_ fingerprint: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return pairedFingerprints.contains(fingerprint)
    }

    private func handlePairRequest(
        _ envelope: Envelope,
        peerFingerprint: String,
        now: Date
    ) -> (replies: [Envelope], close: Bool) {
        guard let token = envelope.payload.objectValue?["token"]?.stringValue else {
            return ([errorReply(to: envelope, code: "bad_payload", message: "token required")], true)
        }

        lock.lock()
        let accepted = tokens.attempt(token: token, now: now)
        if accepted {
            pairedFingerprints.insert(peerFingerprint)
        }
        lock.unlock()

        guard accepted else {
            return ([errorReply(to: envelope, code: "unpaired", message: "invalid or expired token")], true)
        }

        let reply = Envelope(
            re: envelope.id,
            type: "pair.ok",
            payload: .object([
                "deviceId": .string(deviceId),
                "name": .string("Graft Mac"),
                "platform": .string("macos")
            ])
        )
        return ([reply], false)
    }

    private func helloOk(replyTo envelope: Envelope) -> Envelope {
        let caps = (envelope.payload.objectValue?["caps"]?.arrayValue ?? [])
            .compactMap { $0.stringValue }
        return Envelope(
            re: envelope.id,
            type: "hello.ok",
            payload: .object([
                "deviceId": .string(deviceId),
                "name": .string("Graft Mac"),
                "proto": .number(1),
                "caps": .array(caps.map { .string($0) })
            ])
        )
    }

    private var deviceId: String {
        "mac-\(local.fingerprint.prefix(8))"
    }

    private func errorReply(to envelope: Envelope, code: String, message: String) -> Envelope {
        Envelope(
            re: envelope.id,
            type: "error",
            payload: .object([
                "code": .string(code),
                "message": .string(message)
            ])
        )
    }
}

extension JSONValue {
    var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }
}
