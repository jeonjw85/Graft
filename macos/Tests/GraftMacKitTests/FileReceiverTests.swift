import CryptoKit
import Foundation
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

final class SafeFilenameTests: XCTestCase {
    func testRejectsPathTraversal() {
        XCTAssertNil(SafeFilename.sanitize("../../evil.sh"))
        XCTAssertNil(SafeFilename.sanitize(".."))
        XCTAssertNil(SafeFilename.sanitize("."))
        XCTAssertNil(SafeFilename.sanitize("a/b"))
        XCTAssertNil(SafeFilename.sanitize("a\\b"))
        XCTAssertNil(SafeFilename.sanitize("bad\u{0}name"))
        XCTAssertNil(SafeFilename.sanitize(""))
        XCTAssertNil(SafeFilename.sanitize("   "))
    }

    func testRejectsControlCharacters() {
        XCTAssertNil(SafeFilename.sanitize("bell\u{07}.txt"))
        XCTAssertNil(SafeFilename.sanitize("newline\n.txt"))
    }

    func testAllowsUnicodeNames() {
        XCTAssertEqual(SafeFilename.sanitize("보고서 2026.pdf"), "보고서 2026.pdf")
        XCTAssertEqual(SafeFilename.sanitize("사진 👋🏽.png"), "사진 👋🏽.png")
    }

    func testTrimsSurroundingWhitespace() {
        XCTAssertEqual(SafeFilename.sanitize("  report.pdf  "), "report.pdf")
    }

    func testRejectsOverlongNames() {
        XCTAssertNil(SafeFilename.sanitize(String(repeating: "a", count: 300)))
    }

    func testCollisionSuffix() throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertEqual(SafeFilename.unique(in: directory, name: "a.txt"), "a.txt")
        FileManager.default.createFile(
            atPath: directory.appendingPathComponent("a.txt").path,
            contents: Data()
        )

        XCTAssertEqual(SafeFilename.unique(in: directory, name: "a.txt"), "a (2).txt")
    }

    func testCollisionSuffixHandlesPartFilesAndNoExtension() throws {
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        FileManager.default.createFile(
            atPath: directory.appendingPathComponent("notes (2).part").path,
            contents: Data()
        )

        // "notes" itself is free, so it is used even though an unrelated "notes (2).part" exists.
        XCTAssertEqual(SafeFilename.unique(in: directory, name: "notes"), "notes")

        // "notes (2)" is taken by the .part file, so the next candidate is "notes (3)".
        FileManager.default.createFile(
            atPath: directory.appendingPathComponent("notes (2)").path,
            contents: Data()
        )
        XCTAssertEqual(SafeFilename.unique(in: directory, name: "notes (2)"), "notes (3)")
    }

    private func makeDirectory() throws -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-name-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

final class FileReceiverTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-recv-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func payload(_ count: Int) -> Data {
        Data((0..<count).map { UInt8($0 % 251) })
    }

    private func sha256(_ data: Data) -> String {
        Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined()
    }

    private func offer(_ name: String, size: Int) -> FileOffer {
        FileOffer(transferId: 1, name: name, size: UInt64(size), mime: "application/octet-stream")
    }

    func testWritesChunksAndCommitsOnMatchingHash() throws {
        let data = payload(200 * 1024)
        let receiver = FileReceiver(directory: directory)

        let start = try receiver.begin(offer("sample.bin", size: data.count), resumeOffset: 0)
        XCTAssertEqual(start, 0)

        var offset: UInt64 = 0
        for chunk in stride(from: 0, to: data.count, by: 64 * 1024) {
            let end = min(chunk + 64 * 1024, data.count)
            let frame = try BinaryFrame(
                transferId: 1,
                offset: offset,
                data: data.subdata(in: chunk..<end)
            )
            try receiver.write(frame)
            offset += UInt64(end - chunk)
        }

        let url = try receiver.finish(expectedSha256: sha256(data))

        XCTAssertEqual(try Data(contentsOf: url), data)
        let remaining = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertEqual(remaining, ["sample.bin"], "no .part file must remain")
        XCTAssertTrue(isQuarantined(url), "a downloaded file must pass through Gatekeeper")
    }

    private func isQuarantined(_ url: URL) -> Bool {
        url.withUnsafeFileSystemRepresentation { path -> Bool in
            guard let path else { return false }
            return getxattr(path, "com.apple.quarantine", nil, 0, 0, 0) > 0
        }
    }

    func testResumeAppendsFromTheAcceptedOffset() throws {
        let data = payload(200 * 1024)
        let firstHalf = 128 * 1024

        let first = FileReceiver(directory: directory)
        _ = try first.begin(offer("resume.bin", size: data.count), resumeOffset: 0)
        for chunk in stride(from: 0, to: firstHalf, by: 64 * 1024) {
            let end = min(chunk + 64 * 1024, firstHalf)
            try first.write(
                try BinaryFrame(
                    transferId: 1,
                    offset: UInt64(chunk),
                    data: data.subdata(in: chunk..<end)
                )
            )
        }
        let partial = try XCTUnwrap(first.partialURL)
        XCTAssertEqual(try Data(contentsOf: partial), data.subdata(in: 0..<firstHalf))
        first.cancel()

        let second = FileReceiver(directory: directory)
        let resumedAt = try second.begin(
            offer("resume.bin", size: data.count),
            resumeOffset: UInt64(firstHalf)
        )
        XCTAssertEqual(resumedAt, UInt64(firstHalf))

        for chunk in stride(from: firstHalf, to: data.count, by: 64 * 1024) {
            let end = min(chunk + 64 * 1024, data.count)
            try second.write(
                try BinaryFrame(
                    transferId: 1,
                    offset: UInt64(chunk),
                    data: data.subdata(in: chunk..<end)
                )
            )
        }
        let url = try second.finish(expectedSha256: sha256(data))

        XCTAssertEqual(try Data(contentsOf: url), data)
    }

    func testHashMismatchDeletesThePartialFile() throws {
        let data = payload(1024)
        let receiver = FileReceiver(directory: directory)
        _ = try receiver.begin(offer("bad.bin", size: data.count), resumeOffset: 0)
        try receiver.write(try BinaryFrame(transferId: 1, offset: 0, data: data))

        XCTAssertThrowsError(try receiver.finish(expectedSha256: String(repeating: "0", count: 64))) {
            XCTAssertEqual($0 as? FileReceiverError, .hashMismatch)
        }

        let remaining = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertTrue(remaining.isEmpty, "a failed transfer must leave nothing behind")
    }

    func testWrongOffsetIsRejected() throws {
        let data = payload(1024)
        let receiver = FileReceiver(directory: directory)
        _ = try receiver.begin(offer("offset.bin", size: data.count), resumeOffset: 0)

        XCTAssertThrowsError(
            try receiver.write(try BinaryFrame(transferId: 1, offset: 512, data: data))
        ) {
            XCTAssertEqual($0 as? FileReceiverError, .wrongOffset(expected: 0, got: 512))
        }
    }

    func testUnsafeNameIsRejected() {
        let receiver = FileReceiver(directory: directory)
        XCTAssertThrowsError(try receiver.begin(offer("../../evil.sh", size: 1), resumeOffset: 0)) {
            XCTAssertEqual($0 as? FileReceiverError, .unsafeName)
        }
    }

    func testInterruptedTransferLeavesAResumablePartFile() throws {
        let data = payload(100 * 1024)
        let receiver = FileReceiver(directory: directory)
        _ = try receiver.begin(offer("partial.bin", size: data.count), resumeOffset: 0)
        for chunk in stride(from: 0, to: data.count, by: 64 * 1024) {
            let end = min(chunk + 64 * 1024, data.count)
            try receiver.write(
                try BinaryFrame(transferId: 1, offset: UInt64(chunk), data: data.subdata(in: chunk..<end))
            )
        }

        receiver.cancel()

        let remaining = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        XCTAssertEqual(remaining, ["partial.bin.part"])
        XCTAssertEqual(receiver.confirmedBytes, UInt64(data.count))
    }

    func testHashIsComputedFromTheBytesOnDisk() throws {
        let data = payload(4096)
        let receiver = FileReceiver(directory: directory)
        _ = try receiver.begin(offer("disk.bin", size: data.count), resumeOffset: 0)
        try receiver.write(try BinaryFrame(transferId: 1, offset: 0, data: data))

        // Truncate behind the receiver's back. finish() must notice, because the hash has to come
        // from the file rather than from the chunks it happened to see.
        let partial = try XCTUnwrap(receiver.partialURL)
        let handle = try FileHandle(forWritingTo: partial)
        try handle.truncate(atOffset: 1024)
        try handle.close()

        XCTAssertThrowsError(try receiver.finish(expectedSha256: sha256(data)))

        let onDisk = try FileReceiver(directory: directory)
        _ = try onDisk.begin(offer("disk.bin", size: data.count), resumeOffset: 1024)
        XCTAssertNotEqual(try onDisk.digestOnDisk(), sha256(data), "truncated bytes cannot match")
    }

    func testResumeWithMismatchedPartialSizeStartsOver() throws {
        let data = payload(2048)
        _ = data

        let first = FileReceiver(directory: directory)
        _ = try first.begin(offer("mismatch.bin", size: 4096), resumeOffset: 0)
        try first.write(try BinaryFrame(transferId: 1, offset: 0, data: payload(100)))
        first.cancel()

        let second = FileReceiver(directory: directory)
        let resumedAt = try second.begin(offer("mismatch.bin", size: 4096), resumeOffset: 2048)

        XCTAssertEqual(resumedAt, 0, "a partial file of the wrong size must not be trusted")
        XCTAssertEqual(second.confirmedBytes, 0)
    }
}
