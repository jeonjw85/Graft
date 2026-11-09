import Foundation

public enum SessionPhase: String, CaseIterable, Sendable {
    case disconnected, connecting, authenticating, unpaired, pairing, paired, ready, closing, closed
}

public enum CloseReason: String, Sendable, Equatable {
    case pinMismatch, unknownDevice, tokenInvalid, tokenExpired, rateLimited, protoUnsupported, fatal
}

public enum SessionEvent: Equatable, Sendable {
    case connect
    case tlsReady(peerFingerprint: String)
    case tlsFailed(reason: CloseReason)
    case pairRequest
    case pairAccepted
    case pairRejected
    case helloReceived
    case helloAcked
    case messageReceived
    case pingReceived
    case pingTimeout
    case disconnect
    case close

    /// Name used in the shared transition fixture.
    public var fixtureName: String {
        switch self {
        case .connect: "connect"
        case .tlsReady: "tlsReady"
        case .tlsFailed: "tlsFailed"
        case .pairRequest: "pairRequest"
        case .pairAccepted: "pairAccepted"
        case .pairRejected: "pairRejected"
        case .helloReceived: "helloReceived"
        case .helloAcked: "helloAcked"
        case .messageReceived: "messageReceived"
        case .pingReceived: "pingReceived"
        case .pingTimeout: "pingTimeout"
        case .disconnect: "disconnect"
        case .close: "close"
        }
    }

    static func parse(_ name: String) throws -> SessionEvent {
        switch name {
        case "connect": return .connect
        case "tlsReady": return .tlsReady(peerFingerprint: "")
        case "tlsFailed": return .tlsFailed(reason: .fatal)
        case "pairRequest": return .pairRequest
        case "pairAccepted": return .pairAccepted
        case "pairRejected": return .pairRejected
        case "helloReceived": return .helloReceived
        case "helloAcked": return .helloAcked
        case "messageReceived": return .messageReceived
        case "pingReceived": return .pingReceived
        case "pingTimeout": return .pingTimeout
        case "disconnect": return .disconnect
        case "close": return .close
        default: throw SessionError.unknownEvent(name)
        }
    }
}

public enum SessionAction: String, Sendable, Equatable {
    case sendPairOk, sendHello, sendHelloOk, sendPairError, sendErrorUnpaired
    case scheduleReconnect, stopReconnect, startHeartbeat, stopHeartbeat, closeConnection
}

public enum SessionError: Error, Equatable {
    case unknownEvent(String)
    case unknownPhase(String)
    case illegalTransition(from: SessionPhase, event: String)
}

/// The connection state machine shared (by fixture) with the Kotlin implementation.
public struct SessionMachine {
    public private(set) var phase: SessionPhase

    public init(phase: SessionPhase = .disconnected) {
        self.phase = phase
    }

    public mutating func handle(_ event: SessionEvent) -> [SessionAction] {
        switch (phase, event) {
        case (.disconnected, .connect):
            phase = .connecting
            return []

        case (.connecting, .tlsReady):
            phase = .authenticating
            return []

        case (.connecting, .tlsFailed(let reason)),
             (.authenticating, .tlsFailed(let reason)):
            if reason == .pinMismatch {
                phase = .closed
                return [.stopReconnect]
            }
            phase = .disconnected
            return [.scheduleReconnect]

        case (.authenticating, .pairRequest):
            phase = .unpaired
            return []

        case (.authenticating, .helloReceived):
            phase = .ready
            return [.sendHelloOk, .startHeartbeat]

        case (.unpaired, .pairRequest):
            phase = .pairing
            return []

        case (.unpaired, .messageReceived):
            phase = .closing
            return [.sendErrorUnpaired, .closeConnection]

        case (.pairing, .pairAccepted):
            phase = .paired
            return [.sendPairOk, .sendHello]

        case (.pairing, .pairRejected):
            phase = .closing
            return [.closeConnection]

        case (.paired, .helloReceived):
            phase = .ready
            return [.sendHelloOk, .startHeartbeat]

        case (.paired, .helloAcked):
            phase = .ready
            return [.startHeartbeat]

        case (.ready, .helloReceived):
            return [.sendHelloOk]

        case (.ready, .pingReceived), (.ready, .messageReceived):
            return []

        case (.ready, .disconnect):
            phase = .disconnected
            return [.stopHeartbeat, .scheduleReconnect]

        case (.ready, .pingTimeout):
            phase = .disconnected
            return [.stopHeartbeat, .closeConnection, .scheduleReconnect]

        case (.closing, .close):
            phase = .closed
            return [.stopHeartbeat]

        default:
            // Unknown pairs are ignored rather than crashing: a peer can send anything.
            return []
        }
    }
}

/// Exponential backoff with jitter. `jitter` returns a value in `0...upperBound`.
public struct ReconnectBackoff {
    private static let schedule: [Double] = [1, 2, 4, 8, 16, 32, 60]
    private var index = 0
    private let jitter: (Double) -> Double

    public init(jitter: @escaping (Double) -> Double = { Double.random(in: 0...$0) }) {
        self.jitter = jitter
    }

    public mutating func nextDelay() -> Double {
        let base = Self.schedule[min(index, Self.schedule.count - 1)]
        index += 1
        let maximumJitter = base * 0.2
        return base + min(max(jitter(maximumJitter), 0), maximumJitter)
    }

    public mutating func reset() {
        index = 0
    }
}

/// Heartbeat timing: ping every 20 s, dead after 60 s of silence.
public struct Heartbeat {
    public static let interval: TimeInterval = 20
    public static let timeout: TimeInterval = 60

    private var lastPing: Date
    private var lastActivity: Date

    public init(now: Date) {
        self.lastPing = now
        self.lastActivity = now
    }

    public mutating func shouldPing(now: Date) -> Bool {
        guard now.timeIntervalSince(lastPing) >= Self.interval else { return false }
        lastPing = now
        return true
    }

    public mutating func noteActivity(now: Date) {
        lastActivity = now
    }

    public func isDead(now: Date) -> Bool {
        now.timeIntervalSince(lastActivity) >= Self.timeout
    }
}
