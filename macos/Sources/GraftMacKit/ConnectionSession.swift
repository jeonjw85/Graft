import Foundation
import GraftProtocol

/// Drives one accepted connection: protocol rules, session phase, and heartbeat.
///
/// `PairingService` decides what a frame means; this type decides what happens to the connection
/// around it and keeps `SessionMachine` and `Heartbeat` on the hot path rather than only in tests.
public final class ConnectionSession: @unchecked Sendable {
    public private(set) var phase: SessionPhase = .disconnected
    public var peerFingerprint: String?

    private let service: PairingService
    private let send: (Envelope) -> Void
    private let closeTransport: () -> Void
    private var machine = SessionMachine()
    private var heartbeat: Heartbeat

    public init(
        service: PairingService,
        now: Date = Date(),
        send: @escaping (Envelope) -> Void,
        close: @escaping () -> Void
    ) {
        self.service = service
        self.send = send
        self.closeTransport = close
        self.heartbeat = Heartbeat(now: now)
    }

    /// Marks the connection as established. Called from the transport's ready callback.
    public func start(now: Date = Date()) {
        _ = apply(machine.handle(.connect), now: now)
        _ = apply(machine.handle(.tlsReady(peerFingerprint: peerFingerprint ?? "")), now: now)
    }

    /// Routes one inbound control frame through the protocol rules and the state machine.
    @discardableResult
    public func handle(envelope: Envelope, now: Date = Date()) -> [SessionAction] {
        heartbeat.noteActivity(now: now)

        let fingerprint = peerFingerprint ?? ""
        let outcome = service.handle(envelope: envelope, peerFingerprint: fingerprint, now: now)
        for reply in outcome.replies {
            send(reply)
        }

        var actions: [SessionAction] = []

        if envelope.type == "pair.request" {
            actions += apply(machine.handle(.pairRequest), now: now)
            if outcome.close {
                // A refused pairing request is either a rejected pairing (we were in .pairing) or
                // an illegal message (we never entered it).
                actions += phase == .pairing
                    ? apply(machine.handle(.pairRejected), now: now)
                    : apply(machine.handle(.messageReceived), now: now)
            } else {
                actions += apply(machine.handle(.pairAccepted), now: now)
            }
        } else if service.isPaired(fingerprint) {
            actions += apply(machine.handle(sessionEvent(for: envelope)), now: now)
        } else {
            // Unpaired peers may only send pair.request. Anything else closes, and the machine must
            // not treat a hello as the authenticating-to-ready transition.
            actions += apply(machine.handle(.messageReceived), now: now)
        }

        if outcome.close {
            // The machine's .closing phase is the record; .close belongs to whoever observes the
            // socket actually closing, so the transport is torn down without advancing past it.
            closeTransport()
        }
        return actions
    }

    /// Advances timers. Sends a ping every 20 s and closes after 60 s without any inbound frame.
    public func tick(now: Date = Date()) {
        guard phase == .ready else { return }

        if heartbeat.isDead(now: now) {
            _ = apply(machine.handle(.pingTimeout), now: now)
            closeTransport()
            return
        }

        if heartbeat.shouldPing(now: now) {
            send(Envelope(type: "ping", payload: .object([:])))
        }
    }

    /// Reports a TLS-level failure so the machine can decide whether reconnecting is allowed.
    @discardableResult
    public func handleTLSFailure(reason: CloseReason, now: Date = Date()) -> [SessionAction] {
        apply(machine.handle(.tlsFailed(reason: reason)), now: now)
    }

    private func sessionEvent(for envelope: Envelope) -> SessionEvent {
        switch envelope.type {
        case "hello": return .helloReceived
        case "ping": return .pingReceived
        default: return .messageReceived
        }
    }

    @discardableResult
    private func apply(_ actions: [SessionAction], now: Date) -> [SessionAction] {
        phase = machine.phase
        for action in actions {
            switch action {
            case .startHeartbeat:
                heartbeat = Heartbeat(now: now)
            case .closeConnection, .stopHeartbeat, .stopReconnect, .sendPairOk, .sendHello,
                 .sendHelloOk, .sendPairError, .sendErrorUnpaired, .scheduleReconnect:
                // Frames are emitted by PairingService; reconnect scheduling belongs to the app
                // shell that owns the client, not to one server-side connection.
                break
            }
        }
        return actions
    }
}
