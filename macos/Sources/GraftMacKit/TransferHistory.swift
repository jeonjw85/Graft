import Foundation
import GraftProtocol

/// One finished transfer, as the settings window shows it.
public struct MacTransferRecord: Hashable, Codable, Sendable {
    /// The protocol transfer id, so a retry can replace its earlier row.
    public let id: UInt32?
    public let name: String
    public let bytes: UInt64
    public let direction: TransferDirection
    public let failure: TransferFailure?
    public let finishedAt: Date
    /// Absolute path of a received file on disk, so Settings can reveal it in Finder.
    public let destinationPath: String?

    public init(
        id: UInt32?,
        name: String,
        bytes: UInt64,
        direction: TransferDirection,
        failure: TransferFailure?,
        finishedAt: Date,
        destinationPath: String? = nil
    ) {
        self.id = id
        self.name = name
        self.bytes = bytes
        self.direction = direction
        self.failure = failure
        self.finishedAt = finishedAt
        self.destinationPath = destinationPath
    }
}

/// The recent transfers on the Mac, newest first.
///
/// A repeated transfer id replaces its row so a retry does not read as two transfers.
public final class TransferHistory {
    public typealias Clock = () -> Date

    private let capacity: Int
    private let clock: Clock
    private var rows: [MacTransferRecord] = []

    public init(capacity: Int = 50, clock: @escaping Clock = Date.init) {
        self.capacity = capacity
        self.clock = clock
    }

    public var records: [MacTransferRecord] { rows }

    /// Replaces the in-memory list with previously persisted rows, newest first.
    public func restore(_ persisted: [MacTransferRecord]) {
        rows = Array(persisted.sorted { $0.finishedAt > $1.finishedAt }.prefix(capacity))
    }

    @discardableResult
    public func record(
        id: UInt32? = nil,
        name: String,
        bytes: UInt64,
        direction: TransferDirection,
        failure: TransferFailure?,
        destinationPath: String? = nil
    ) -> MacTransferRecord {
        if let id {
            // Transfer ids restart with every phone session, so a retry is the same id *and* the
            // same file; a different file that happens to reuse the id keeps its own row.
            rows.removeAll { $0.id == id && $0.name == name && $0.direction == direction }
        }

        let row = MacTransferRecord(
            id: id,
            name: name,
            bytes: bytes,
            direction: direction,
            failure: failure,
            finishedAt: clock(),
            destinationPath: destinationPath
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
        case .diskFull: return "Disk full"
        case .stalled: return "Timed out"
        }
    }

    public static func describe(_ direction: TransferDirection) -> String {
        direction == .receive ? "Received" : "Sent"
    }
}

/// Persists recent macOS transfer records to disk so history survives restarts.
public final class TransferHistoryStore {
    private let file: URL
    private let lock = NSLock()

    public init(file: URL) {
        self.file = file
    }

    public func load() -> [MacTransferRecord] {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: file), !data.isEmpty else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return (try? decoder.decode([MacTransferRecord].self, from: data)) ?? []
    }

    public func save(_ records: [MacTransferRecord]) {
        lock.lock()
        defer { lock.unlock() }
        let directory = file.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970
        if let data = try? encoder.encode(records) {
            try? data.write(to: file, options: .atomic)
        }
    }

    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        try? FileManager.default.removeItem(at: file)
    }
}
