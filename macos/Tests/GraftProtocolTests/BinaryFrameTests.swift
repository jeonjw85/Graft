import Foundation
import XCTest
@testable import GraftProtocol

final class BinaryFrameTests: XCTestCase {
    func testFixtureRoundTrip() throws {
        let fixture = try BinaryFixtures.load()
        XCTAssertEqual(fixture.frames.count, 4)

        for row in fixture.frames {
            let frame = try BinaryFrame.decode(Data(hexString: row.encodedHex))
            XCTAssertEqual(frame.transferId, row.tid, row.name)
            XCTAssertEqual(frame.offset, row.offset, row.name)
            XCTAssertEqual(frame.data, Data(hexString: row.dataHex), row.name)
            XCTAssertEqual(frame.encoded(), Data(hexString: row.encodedHex), row.name)
        }
    }

    func testRejectsInvalidFrames() throws {
        let fixture = try BinaryFixtures.load()
        XCTAssertFalse(fixture.invalid.isEmpty)

        for row in fixture.invalid {
            XCTAssertThrowsError(try BinaryFrame.decode(Data(hexString: row.encodedHex)), row.name)
        }
    }

    func testRejectsInvalidFramesWithTheExpectedError() {
        XCTAssertThrowsError(try BinaryFrame.decode(Data())) {
            XCTAssertEqual($0 as? BinaryFrameError, .tooShort)
        }
        XCTAssertThrowsError(try BinaryFrame.decode(Data(repeating: 0, count: 15))) {
            XCTAssertEqual($0 as? BinaryFrameError, .tooShort)
        }
        XCTAssertThrowsError(
            try BinaryFrame.decode(Data(hexString: "58580100000000000000000000000000"))
        ) {
            XCTAssertEqual($0 as? BinaryFrameError, .badMagic)
        }
        XCTAssertThrowsError(
            try BinaryFrame.decode(Data(hexString: "47460200000000000000000000000000"))
        ) {
            XCTAssertEqual($0 as? BinaryFrameError, .unsupportedVersion)
        }
        XCTAssertThrowsError(
            try BinaryFrame.decode(Data(hexString: "47460101000000000000000000000000"))
        ) {
            XCTAssertEqual($0 as? BinaryFrameError, .reservedNotZero)
        }
    }

    func testRejectsOversizedPayload() {
        var raw = Data([0x47, 0x46, 0x01, 0x00, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0])
        raw.append(Data(repeating: 0, count: BinaryFrame.maxDataSize + 1))

        XCTAssertThrowsError(try BinaryFrame.decode(raw)) {
            XCTAssertEqual($0 as? BinaryFrameError, .oversizedPayload)
        }
    }

    func testAcceptsExactlyTheMaximumPayload() {
        var raw = Data([0x47, 0x46, 0x01, 0x00, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0])
        raw.append(Data(repeating: 0xAB, count: BinaryFrame.maxDataSize))

        let frame = try? BinaryFrame.decode(raw)
        XCTAssertEqual(frame?.data.count, BinaryFrame.maxDataSize)
    }

    func testHeaderSizeIsSixteen() {
        XCTAssertEqual(BinaryFrame.headerSize, 16)
        XCTAssertEqual(BinaryFrame.maxDataSize, 64 * 1024)
    }
}

struct BinaryFixtures {
    struct Frame {
        let name: String
        let tid: UInt32
        let offset: UInt64
        let dataHex: String
        let encodedHex: String
    }

    struct Invalid {
        let name: String
        let encodedHex: String
    }

    let frames: [Frame]
    let invalid: [Invalid]

    static func load() throws -> BinaryFixtures {
        let url = FixtureLoader.directory.appendingPathComponent("binary/frames.json")
        let root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        )

        let frames = try (root["frames"] as? [[String: Any]] ?? []).map { row -> Frame in
            Frame(
                name: try XCTUnwrap(row["name"] as? String),
                tid: try XCTUnwrap((row["tid"] as? NSNumber)?.uint32Value),
                offset: try XCTUnwrap((row["offset"] as? NSNumber)?.uint64Value),
                dataHex: try XCTUnwrap(row["dataHex"] as? String),
                encodedHex: try XCTUnwrap(row["encodedHex"] as? String)
            )
        }

        let invalid = (root["invalid"] as? [[String: Any]] ?? []).map { row in
            Invalid(
                name: row["name"] as? String ?? "?",
                encodedHex: row["encodedHex"] as? String ?? ""
            )
        }

        return BinaryFixtures(frames: frames, invalid: invalid)
    }
}
