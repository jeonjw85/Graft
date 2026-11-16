import Foundation

/// A transfer that can still be resumed.
public struct ResumeRecord: Codable, Equatable, Sendable {
    public let name: String
    public let directory: String
    public let size: UInt64
    public let bytesConfirmed: UInt64
    public let updatedAtMillis: Int64

    public init(
        name: String,
        directory: String,
        size: UInt64,
        bytesConfirmed: UInt64,
        updatedAtMillis: Int64
    ) {
        self.name = name
        self.directory = directory
        self.size = size
        self.bytesConfirmed = bytesConfirmed
        self.updatedAtMillis = updatedAtMillis
    }
}

/// Remembers partial transfers so a restart can continue instead of starting over.
///
/// A record matches only when the name, directory, and total size agree and the bytes on disk equal
/// `bytesConfirmed`. A record older than the TTL is deleted, not just ignored.
public final class TransferRecordStore {
    public static let defaultTTLMillis: Int64 = 24 * 60 * 60 * 1000

    private let file: URL
    private let clock: () -> Int64
    private let ttlMillis: Int64
    private let lock = NSLock()

    public init(
        file: URL,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        ttlMillis: Int64 = TransferRecordStore.defaultTTLMillis
    ) {
        self.file = file
        self.clock = clock
        self.ttlMillis = ttlMillis
    }

    public func find(
        name: String,
        directory: String,
        size: UInt64,
        partialBytesOnDisk: UInt64
    ) -> ResumeRecord? {
        lock.lock()
        defer { lock.unlock() }

        let records = loadLocked()
        guard let record = records.first(where: {
            $0.name == name && $0.directory == directory && $0.size == size
        }) else {
            return nil
        }

        if clock() - record.updatedAtMillis >= ttlMillis {
            saveLocked(records.filter { !($0.name == name && $0.directory == directory) })
            return nil
        }
        guard partialBytesOnDisk > 0, record.bytesConfirmed == partialBytesOnDisk else {
            return nil
        }
        return record
    }

    public func upsert(_ record: ResumeRecord) {
        lock.lock()
        defer { lock.unlock() }

        var records = loadLocked().filter {
            !($0.name == record.name && $0.directory == record.directory)
        }
        records.append(record)
        saveLocked(records)
    }

    public func remove(name: String, directory: String) {
        lock.lock()
        defer { lock.unlock() }
        saveLocked(loadLocked().filter { !($0.name == name && $0.directory == directory) })
    }

    @discardableResult
    public func purgeExpired() -> Int {
        lock.lock()
        defer { lock.unlock() }

        let now = clock()
        let records = loadLocked()
        let kept = records.filter { now - $0.updatedAtMillis < ttlMillis }
        if kept.count != records.count {
            saveLocked(kept)
        }
        return records.count - kept.count
    }

    public func count() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return loadLocked().count
    }

    private func loadLocked() -> [ResumeRecord] {
        guard let data = try? Data(contentsOf: file), !data.isEmpty else { return [] }
        return (try? JSONDecoder().decode(StoreFile.self, from: data))?.records ?? []
    }

    private func saveLocked(_ records: [ResumeRecord]) {
        let directory = file.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? encoder.encode(StoreFile(records: records)) {
            try? data.write(to: file, options: .atomic)
        }
    }

    private struct StoreFile: Codable {
        let records: [ResumeRecord]
    }
}
