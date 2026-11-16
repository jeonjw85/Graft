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

    /// The peer from the most recently accepted pair.request, so the app can persist its name.
    public private(set) var lastPairedPeer: (fingerprint: String, name: String)?
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

    /// Removes a paired phone from the TLS allow list and from the pending-persist record.
    ///
    /// Without this, "Forget" only edited the app's own list: the TLS layer kept admitting the
    /// phone and the next message re-added it from `lastPairedPeer`.
    public func forget(fingerprint: String) {
        lock.lock()
        defer { lock.unlock() }
        pairedFingerprints.remove(fingerprint)
        if lastPairedPeer?.fingerprint == fingerprint {
            lastPairedPeer = nil
        }
    }

    /// Drops the current offer. Used when the pairing window closes: the token must die with it.
    public func revokePairing() {
        lock.lock()
        defer { lock.unlock() }
        tokens.revoke()
    }

    /// True while an unused, unexpired token exists. The TLS layer admits unknown peers only then.
    public func isPairingOpen(now: Date = Date()) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return tokens.isActive(now: now)
    }

    /// The values a user types in when they cannot scan a QR code.
    public struct NumericPairing: Equatable, Sendable {
        public let code: String
        public let fingerprintPrefix: String
        public let fullFingerprint: String
        public let host: String
        public let port: UInt16
    }

    /// Issues a six-digit code for manual entry and reports the fingerprint prefix to compare.
    public func beginNumericPairing(
        now: Date,
        host: String,
        port: UInt16,
        deviceName: String
    ) -> NumericPairing {
        lock.lock()
        let token = tokens.issueNumericCode(now: now)
        lock.unlock()

        return NumericPairing(
            code: token.value,
            fingerprintPrefix: String(local.fingerprint.prefix(8)),
            fullFingerprint: local.fingerprint,
            host: host,
            port: port
        )
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

    func isPaired(_ fingerprint: String) -> Bool {
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

        let peerName = envelope.payload.objectValue?["name"]?.stringValue ?? "Phone"

        lock.lock()
        let accepted = tokens.attempt(token: token, now: now)
        if accepted {
            pairedFingerprints.insert(peerFingerprint)
            lastPairedPeer = (fingerprint: peerFingerprint, name: peerName)
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
    public var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }
}
