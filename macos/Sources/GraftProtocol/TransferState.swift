import Foundation

public enum TransferPhase: String, CaseIterable, Sendable {
    case idle, offered, accepted, sending, receiving, verifying, completed, failed, cancelled
}

public enum TransferDirection: String, Codable, Sendable {
    case send, receive
}

public struct FileOffer: Equatable, Sendable {
    public let transferId: UInt32
    public let name: String
    public let size: UInt64
    public let mime: String

    public init(transferId: UInt32, name: String, size: UInt64, mime: String) {
        self.transferId = transferId
        self.name = name
        self.size = size
        self.mime = mime
    }
}

public struct TransferRecord: Equatable, Sendable {
    public let transferId: UInt32
    public var phase: TransferPhase
    public var name: String
    public let size: UInt64
    public var bytesConfirmed: UInt64
    public let sha256: String?
    public let direction: TransferDirection
    public let startedAt: Date

    public init(
        transferId: UInt32,
        phase: TransferPhase = .idle,
        name: String,
        size: UInt64,
        bytesConfirmed: UInt64 = 0,
        sha256: String? = nil,
        direction: TransferDirection,
        startedAt: Date
    ) {
        self.transferId = transferId
        self.phase = phase
        self.name = name
        self.size = size
        self.bytesConfirmed = bytesConfirmed
        self.sha256 = sha256
        self.direction = direction
        self.startedAt = startedAt
    }
}

public enum TransferEvent: Equatable, Sendable {
    case offer
    case accept(offset: UInt64)
    case reject
    case chunk(offset: UInt64, length: Int)
    case complete
    case hashOk
    case hashMismatch
    case stall
    case cancel
}

public enum TransferAction: Equatable, Sendable {
    case abort
    case computeHash
    case commitFile
    case deletePartial
    case seekTo(UInt64)
}

/// The transfer state machine shared (by fixture) with the Kotlin implementation.
///
/// The offset rule is the whole point: a chunk is accepted only when its offset equals
/// `bytesConfirmed`. Anything else would write a hole or duplicate bytes and still leave a file of
/// the right length.
public struct TransferMachine {
    public private(set) var record: TransferRecord
    private var lastActivity: Date

    public init(record: TransferRecord, now: Date? = nil) {
        self.record = record
        self.lastActivity = now ?? record.startedAt
    }

    public var phase: TransferPhase { record.phase }

    /// Test hook for driving fixture rows from an arbitrary phase.
    public mutating func forcePhase(_ phase: TransferPhase) {
        record.phase = phase
    }

    public mutating func tick(now: Date) -> [TransferAction] {
        guard record.phase == .receiving || record.phase == .sending else { return [] }
        guard now.timeIntervalSince(lastActivity) >= 30 else { return [] }

        record.phase = .failed
        return [.abort]
    }

    public mutating func handle(_ event: TransferEvent) -> [TransferAction] {
        switch (record.phase, event) {
        case (.idle, .offer):
            record.phase = .offered
            return []

        case (.offered, .accept(let offset)):
            guard offset >= record.bytesConfirmed else {
                // Every byte before bytesConfirmed is already written. Seeking backwards would
                // duplicate data, so refuse it.
                record.phase = .failed
                return [.abort]
            }
            guard offset <= record.size else {
                record.phase = .failed
                return [.abort]
            }
            record.bytesConfirmed = offset
            record.phase = .receiving
            return offset == 0 ? [] : [.seekTo(offset)]

        case (.offered, .reject):
            record.phase = .failed
            return []

        case (.offered, .cancel):
            record.phase = .cancelled
            return []

        case (.receiving, .chunk(let offset, let length)):
            guard offset == record.bytesConfirmed else {
                record.phase = .failed
                return [.abort]
            }
            record.bytesConfirmed += UInt64(length)
            lastActivity = Date()
            return []

        case (.receiving, .complete):
            record.phase = .verifying
            return [.computeHash]

        case (.verifying, .hashOk):
            record.phase = .completed
            return [.commitFile]

        case (.verifying, .hashMismatch):
            record.phase = .failed
            return [.deletePartial]

        case (.receiving, .stall):
            record.phase = .failed
            return [.abort]

        case (.receiving, .cancel):
            record.phase = .cancelled
            return [.deletePartial]

        default:
            // A frame in the wrong phase is ignored rather than failing the transfer: peers can
            // race a cancel against a chunk that was already in flight.
            return []
        }
    }
}
