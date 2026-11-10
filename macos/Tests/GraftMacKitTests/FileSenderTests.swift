import CryptoKit
import Foundation
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

final class FileSenderTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-send-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeFile(_ bytes: Int, name: String = "sample.bin") throws -> URL {
        let url = directory.appendingPathComponent(name)
        let data = Data((0..<bytes).map { UInt8($0 % 251) })
        try data.write(to: url)
        return url
    }

    func testStreamsExactChunksWithMonotonicOffsets() throws {
        let url = try makeFile(200 * 1024)
        let sender = try FileSender(url: url)

        let frames = try sender.frames(transferId: 3, from: 0)

        XCTAssertEqual(frames.map(\.data.count), [64 * 1024, 64 * 1024, 64 * 1024, 8 * 1024])
        XCTAssertEqual(frames.map(\.offset), [0, 65536, 131072, 196608])
        XCTAssertTrue(frames.allSatisfy { $0.transferId == 3 })
    }

    func testResumeStartsAtTheGivenOffset() throws {
        let url = try makeFile(200 * 1024)
        let sender = try FileSender(url: url)

        let frames = try sender.frames(transferId: 1, from: 131_072)

        XCTAssertEqual(frames.first?.offset, 131_072)
        XCTAssertEqual(frames.reduce(0) { $0 + $1.data.count }, 200 * 1024 - 131_072)
    }

    func testCompleteReturnsTheSha256OfTheWholeFile() throws {
        let url = try makeFile(150 * 1024)
        let sender = try FileSender(url: url)

        let whole = SHA256.hash(data: try Data(contentsOf: url))
        let expected = Data(whole).map { String(format: "%02x", $0) }.joined()

        XCTAssertEqual(try sender.sha256(), expected)
    }

    func testShortFileProducesOneFrame() throws {
        let url = try makeFile(10)
        let sender = try FileSender(url: url)

        let frames = try sender.frames(transferId: 9, from: 0)

        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0].data.count, 10)
        XCTAssertEqual(frames[0].offset, 0)
    }

    func testEmptyFileProducesNoFrames() throws {
        let url = try makeFile(0)
        let sender = try FileSender(url: url)

        XCTAssertTrue(try sender.frames(transferId: 1, from: 0).isEmpty)
        XCTAssertEqual(try sender.sha256(), SHA256.hash(data: Data()).map { String(format: "%02x", $0) }.joined())
    }

    func testOffsetBeyondEndIsRejected() throws {
        let url = try makeFile(10)
        let sender = try FileSender(url: url)

        XCTAssertThrowsError(try sender.frames(transferId: 1, from: 11)) {
            XCTAssertEqual($0 as? FileSenderError, .offsetBeyondEnd)
        }
    }

    func testOfferCarriesNameAndSize() throws {
        let url = try makeFile(1024, name: "보고서.pdf")
        let sender = try FileSender(url: url)

        let offer = sender.offer(transferId: 5)

        XCTAssertEqual(offer.name, "보고서.pdf")
        XCTAssertEqual(offer.size, 1024)
        XCTAssertEqual(offer.transferId, 5)
    }

    func testChunkSizeIsCappedAtTheFrameLimit() throws {
        let url = try makeFile(100)
        let sender = try FileSender(url: url, chunkSize: 1024 * 1024)

        XCTAssertEqual(sender.chunkSize, BinaryFrame.maxDataSize)
    }

    func testStreamMatchesFrames() async throws {
        let url = try makeFile(150 * 1024)
        let sender = try FileSender(url: url)

        var streamed: [BinaryFrame] = []
        for try await frame in try sender.stream(transferId: 2, from: 0) {
            streamed.append(frame)
        }

        let expected = try sender.frames(transferId: 2, from: 0)
        XCTAssertEqual(streamed, expected)
    }
}
