import Foundation
import GraftProtocol

/// One finished transfer, as the settings window shows it.
public struct MacTransferRecord: Equatable, Sendable {
    /// The protocol transfer id, so a retry can replace its earlier row.
    public let id: UInt32?
    public let name: String
    public let bytes: UInt64
    public let direction: TransferDirection
    public let failure: TransferFailure?
    public let finishedAt: Date
}

/// The recent transfers on the Mac, newest first.
///
/// In memory on purpose: the received files are the durable record. A repeated transfer id replaces
/// its row so a retry does not read as two transfers.
public final class TransferHistory {
    public typealias Clock = () -> Date

    private let capacity: Int
    private let clock: Clock
    private var rows: [MacTransferRecord] = []

    public init(capacity: Int = 20, clock: @escaping Clock = Date.init) {
        self.capacity = capacity
        self.clock = clock
    }

    public var records: [MacTransferRecord] { rows }

    @discardableResult
    public func record(
        id: UInt32? = nil,
        name: String,
        bytes: UInt64,
        direction: TransferDirection,
        failure: TransferFailure?
    ) -> MacTransferRecord {
        if let id {
            // The same transfer is one row: a retry replaces, so an earlier failure does not stay.
            rows.removeAll { $0.id == id }
        }

        let row = MacTransferRecord(
            id: id,
            name: name,
            bytes: bytes,
            direction: direction,
            failure: failure,
            finishedAt: clock()
        )
        rows.insert(row, at: 0)
        if rows.count > capacity { rows.removeLast(rows.count - capacity) }
        return row
    }

    public func clear() {
        rows.removeAll()
    }

    public static func describe(_ failure: TransferFailure?) -> String {
        switch failure {
        case nil: return "Completed"
        case .unpairedPeer: return "Declined"
        case .duplicateTransfer: return "Already running"
        case .unsafeName: return "Name not allowed"
        case .wrongOffset: return "Interrupted"
        case .hashMismatch: return "Hash mismatch"
        }
    }

    public static func describe(_ direction: TransferDirection) -> String {
        direction == .receive ? "Received" : "Sent"
    }
}
