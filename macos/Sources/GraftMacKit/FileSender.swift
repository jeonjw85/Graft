import CryptoKit
import Foundation
import GraftProtocol

public enum FileSenderError: Error, Equatable {
    case unreadableFile
    case offsetBeyondEnd
    case alreadyFinished
}

/// Streams a file as 64 KiB binary frames and computes its SHA-256.
///
/// The digest covers the whole file, including bytes sent before a resume, so the receiver can
/// verify the assembled result.
/// @unchecked Sendable: created on one side and handed to the send queue, which reads the file
/// sequentially and never shares the handle.
public final class FileSender: @unchecked Sendable {
    public let url: URL
    public let size: UInt64
    public let name: String
    public let chunkSize: Int

    private var handle: FileHandle?
    private var readOffset: UInt64 = 0

    public init(url: URL, chunkSize: Int = 64 * 1024) throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attributes[.size] as? NSNumber else {
            throw FileSenderError.unreadableFile
        }
        self.url = url
        self.size = size.uint64Value
        self.name = url.lastPathComponent
        self.chunkSize = min(chunkSize, BinaryFrame.maxDataSize)
    }

    public func offer(transferId: UInt32, mime: String = "application/octet-stream") -> FileOffer {
        FileOffer(transferId: transferId, name: name, size: size, mime: mime)
    }

    /// Opens the file at `offset` and returns frames from there.
    public func frames(transferId: UInt32, from offset: UInt64) throws -> [BinaryFrame] {
        guard offset <= size else { throw FileSenderError.offsetBeyondEnd }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: offset)

        var frames: [BinaryFrame] = []
        var position = offset
        while true {
            let chunk = try handle.read(upToCount: chunkSize) ?? Data()
            if chunk.isEmpty { break }
            frames.append(try BinaryFrame(transferId: transferId, offset: position, data: chunk))
            position += UInt64(chunk.count)
        }
        return frames
    }

    /// Reads the file one chunk at a time and hands it to `body`, so nothing is buffered.
    ///
    /// Unlike ``frames(transferId:from:)`` this never builds an array of the whole file.
    public func forEachChunk(
        transferId: UInt32,
        from offset: UInt64,
        _ body: (BinaryFrame) throws -> Void
    ) throws {
        guard offset <= size else { throw FileSenderError.offsetBeyondEnd }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        try handle.seek(toOffset: offset)

        var position = offset
        while true {
            let chunk = try handle.read(upToCount: chunkSize) ?? Data()
            if chunk.isEmpty { break }
            try body(try BinaryFrame(transferId: transferId, offset: position, data: chunk))
            position += UInt64(chunk.count)
        }
    }

    /// Streams frames one at a time so a large file is never held in memory.
    public func stream(transferId: UInt32, from offset: UInt64) throws -> AsyncThrowingStream<BinaryFrame, Error> {
        guard offset <= size else { throw FileSenderError.offsetBeyondEnd }

        let handle = try FileHandle(forReadingFrom: url)
        try handle.seek(toOffset: offset)
        self.handle = handle
        self.readOffset = offset

        return AsyncThrowingStream { continuation in
            continuation.onTermination = { _ in
                try? handle.close()
            }

            // Deliberately synchronous pumping: the caller drives the connection and applies its
            // own backpressure, so this does not spawn an unbounded task.
            do {
                var position = offset
                while true {
                    let chunk = try handle.read(upToCount: self.chunkSize) ?? Data()
                    if chunk.isEmpty { break }
                    continuation.yield(
                        try BinaryFrame(transferId: transferId, offset: position, data: chunk)
                    )
                    position += UInt64(chunk.count)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
    }

    /// SHA-256 of the whole file, read from disk.
    public func sha256() throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: chunkSize), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return Data(hasher.finalize()).map { String(format: "%02x", $0) }.joined()
    }

    public func close() {
        try? handle?.close()
        handle = nil
    }
}
