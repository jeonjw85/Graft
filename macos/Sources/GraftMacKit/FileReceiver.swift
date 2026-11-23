import Foundation
import CryptoKit
import GraftProtocol

/// Keeps a received file name inside the destination directory.
public enum SafeFilename {
    public static let maxNameBytes = 255

    /// Returns a name that cannot escape the destination, or nil when the input is unusable.
    public static func sanitize(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard !trimmed.contains("\u{0}") else { return nil }

        // Reject anything with a path separator: the offer carries a name, never a path.
        guard !trimmed.contains("/"), !trimmed.contains("\\") else { return nil }
        guard trimmed != ".", trimmed != ".." else { return nil }

        // Reject control characters outright; they break Finder and most file APIs.
        guard !trimmed.unicodeScalars.contains(where: { $0.value < 0x20 }) else { return nil }

        guard trimmed.utf8.count <= maxNameBytes else { return nil }
        return trimmed
    }

    /// Appends " (2)", " (3)", ... before the extension until the name is free.
    public static func unique(in directory: URL, name: String) -> String {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: directory.appendingPathComponent(name).path) else {
            return name
        }

        let ext = (name as NSString).pathExtension
        let withoutExtension = (name as NSString).deletingPathExtension

        // If the name already ends in " (n)", continue from n+1 instead of nesting suffixes.
        var base = withoutExtension
        var index = 2
        if let range = withoutExtension.range(of: #" \((\d+)\)$"#, options: .regularExpression),
           let existing = Int(withoutExtension[range].dropFirst(2).dropLast()) {
            base = String(withoutExtension[..<range.lowerBound])
            index = existing + 1
        }

        while index < 1000 {
            let candidate = ext.isEmpty ? "\(base) (\(index))" : "\(base) (\(index)).\(ext)"
            let path = directory.appendingPathComponent(candidate).path
            if !fileManager.fileExists(atPath: path) &&
                !fileManager.fileExists(atPath: path + ".part") {
                return candidate
            }
            index += 1
        }
        return "\(base) (\(UUID().uuidString.prefix(8)))\(ext.isEmpty ? "" : ".\(ext)")"
    }
}

/// Finds bytes already on disk for a name, so a new connection can resume an earlier attempt.
public enum ResumeLookup {
    /// Returns the size of `name.part` when it is a plausible prefix of [size], otherwise 0.
    public static func offset(in directory: URL, name: String, size: UInt64) -> UInt64 {
        guard let safe = SafeFilename.sanitize(name) else { return 0 }
        let part = directory.appendingPathComponent(safe + ".part")
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: part.path),
              let partial = (attributes[.size] as? NSNumber)?.uint64Value,
              partial > 0, partial <= size else {
            return 0
        }
        return partial
    }
}

public enum FileReceiverError: Error, Equatable {
    case unsafeName
    case wrongOffset(expected: UInt64, got: UInt64)
    case notStarted
    case hashMismatch
    case alreadyFinished
}

/// Writes an incoming transfer into `directory`, resuming from an accepted offset.
///
/// The file is written as `name.part` and only renamed when the hash of the bytes on disk matches
/// the sender's value. An interrupted transfer leaves the `.part` file so the offset can be resumed.
public final class FileReceiver {
    private let directory: URL
    private let fileManager = FileManager.default

    private var partURL: URL?
    private var finalURL: URL?
    private var expectedHash: String?
    private var hasher = SHA256()
    private var written: UInt64 = 0
    private var handle: FileHandle?
    private var isFinished = false

    public init(directory: URL) {
        self.directory = directory
    }

    /// Opens (or creates) the partial file and returns the offset the transfer will continue from.
    public func begin(_ offer: FileOffer, resumeOffset: UInt64) throws -> UInt64 {
        guard let name = SafeFilename.sanitize(offer.name) else {
            throw FileReceiverError.unsafeName
        }

        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        // A resumed transfer must reuse its existing .part file, not pick a fresh name just because
        // the .part makes the plain name look taken.
        let partCandidate = directory.appendingPathComponent(name + ".part")
        let resumable = resumeOffset > 0
            && fileManager.fileExists(atPath: partCandidate.path)
            && (try? fileManager.attributesOfItem(atPath: partCandidate.path)[.size] as? NSNumber)?
                .uint64Value == resumeOffset
        let unique = resumable ? name : SafeFilename.unique(in: directory, name: name)
        let part = directory.appendingPathComponent(unique + ".part")
        let target = directory.appendingPathComponent(unique)

        partURL = part
        finalURL = target
        expectedHash = nil
        isFinished = false

        if fileManager.fileExists(atPath: part.path), resumeOffset > 0 {
            let existing = try fileManager.attributesOfItem(atPath: part.path)[.size] as? NSNumber
            let existingSize = existing?.uint64Value ?? 0
            guard existingSize == resumeOffset else {
                // The bytes on disk are not what the sender thinks we have.
                try? fileManager.removeItem(at: part)
                written = 0
                hasher = SHA256()
                fileManager.createFile(atPath: part.path, contents: nil)
                handle = try FileHandle(forWritingTo: part)
                return 0
            }

            // Re-hash what is already there so the final digest covers the whole file.
            hasher = SHA256()
            let reader = try FileHandle(forReadingFrom: part)
            while let chunk = try reader.read(upToCount: 64 * 1024), !chunk.isEmpty {
                hasher.update(data: chunk)
            }
            try reader.close()

            handle = try FileHandle(forWritingTo: part)
            try handle?.seekToEnd()
            written = resumeOffset
            return resumeOffset
        }

        if fileManager.fileExists(atPath: part.path) {
            try? fileManager.removeItem(at: part)
        }
        fileManager.createFile(atPath: part.path, contents: nil)
        handle = try FileHandle(forWritingTo: part)
        hasher = SHA256()
        written = 0
        return 0
    }

    public func write(_ frame: BinaryFrame) throws {
        guard let handle, let partURL else { throw FileReceiverError.notStarted }
        guard !isFinished else { throw FileReceiverError.alreadyFinished }
        guard frame.offset == written else {
            throw FileReceiverError.wrongOffset(expected: written, got: frame.offset)
        }

        try handle.write(contentsOf: frame.data)
        hasher.update(data: frame.data)
        written += UInt64(frame.data.count)
        _ = partURL
    }

    /// Verifies the digest of the bytes on disk and moves the file into place.
    public func finish(expectedSha256: String) throws -> URL {
        guard let handle, let partURL, let finalURL else {
            throw FileReceiverError.notStarted
        }
        guard !isFinished else { throw FileReceiverError.alreadyFinished }

        try handle.synchronize()
        try handle.close()
        self.handle = nil

        // Hash the bytes that actually landed on disk. Hashing what we received in memory would
        // miss a truncated or short write.
        let actual = try digestOnDisk()
        guard actual == expectedSha256.lowercased() else {
            try? fileManager.removeItem(at: partURL)
            // A failed verification leaves no partial file, so the next attempt starts clean.
            written = 0
            isFinished = true
            throw FileReceiverError.hashMismatch
        }

        if fileManager.fileExists(atPath: finalURL.path) {
            _ = try fileManager.replaceItemAt(finalURL, withItemAt: partURL)
        } else {
            try fileManager.moveItem(at: partURL, to: finalURL)
        }
        Self.markQuarantined(finalURL)
        isFinished = true
        return finalURL
    }

    /// A file that arrived over the network must pass Gatekeeper, or a pushed `.pkg` or `.command`
    /// would open without the "downloaded" warning.
    private static func markQuarantined(_ url: URL) {
        let value = "0081;00000000;Graft;"
        value.withCString { pointer in
            _ = setxattr(url.path, "com.apple.quarantine", pointer, strlen(pointer), 0, 0)
        }
    }

    /// Recomputes the digest from the partial file. Used when a transfer is resumed across launches.
    public func digestOnDisk() throws -> String {
        guard let partURL else { throw FileReceiverError.notStarted }
        let reader = try FileHandle(forReadingFrom: partURL)
        defer { try? reader.close() }

        var fresh = SHA256()
        while let chunk = try reader.read(upToCount: 64 * 1024), !chunk.isEmpty {
            fresh.update(data: chunk)
        }
        return Data(fresh.finalize()).map { String(format: "%02x", $0) }.joined()
    }

    public func cancel() {
        if let handle {
            try? handle.close()
            self.handle = nil
        }
        isFinished = true
    }

    /// Closes the handle and deletes the partial file on disk (used when a transfer stalls).
    public func discard() {
        cancel()
        if let partURL {
            try? fileManager.removeItem(at: partURL)
        }
        written = 0
    }

    public var confirmedBytes: UInt64 { written }

    /// The partial file, for the resumable record.
    public var partialURL: URL? { partURL }
}
