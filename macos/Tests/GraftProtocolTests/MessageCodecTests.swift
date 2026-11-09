import XCTest
@testable import GraftProtocol

final class MessageCodecTests: XCTestCase {
    func testDecodeHelloFixture() throws {
        let envelope = try MessageCodec.decode(try FixtureLoader.data("hello"))

        XCTAssertEqual(envelope.v, 1)
        XCTAssertEqual(envelope.type, "hello")
        guard case .object(let payload) = envelope.payload else {
            return XCTFail("payload is not an object")
        }
        XCTAssertEqual(payload["deviceId"], .string("mac-7f3a9c"))
        XCTAssertEqual(payload["appVersion"], .string("0.1.0"))
    }

    func testRoundTripAllFixtures() throws {
        let names = try FixtureLoader.names()
        XCTAssertEqual(names.count, 15, "모든 픽스처를 라운드트립해야 한다")

        for name in names {
            let decoded = try MessageCodec.decode(try FixtureLoader.data(name))
            let reencoded = try MessageCodec.encode(decoded)
            XCTAssertEqual(try MessageCodec.decode(reencoded), decoded, name)
        }
    }

    func testRejectUnsupportedVersion() {
        let json = #"{"v":2,"id":"x","type":"hello","ts":1,"payload":{}}"#
        XCTAssertThrowsError(try MessageCodec.decode(Data(json.utf8))) { error in
            XCTAssertEqual(error as? CodecError, .unsupportedVersion(2))
        }
    }

    func testOversizedBoundary() {
        XCTAssertThrowsError(try MessageCodec.decode(Data(count: MessageCodec.maxControlFrameBytes))) { error in
            XCTAssertEqual(error as? CodecError, .malformed("invalid utf8 or json"))
        }
        XCTAssertThrowsError(try MessageCodec.decode(Data(count: MessageCodec.maxControlFrameBytes + 1))) { error in
            XCTAssertEqual(error as? CodecError, .oversized(MessageCodec.maxControlFrameBytes + 1))
        }
    }

    func testMalformedInputsFailCleanly() {
        let inputs = [Data(#"{"v":1"#.utf8), Data(), Data([0xFF, 0xFE])]
        for input in inputs {
            XCTAssertThrowsError(try MessageCodec.decode(input)) { error in
                XCTAssertEqual(error as? CodecError, .malformed("invalid utf8 or json"))
            }
        }
    }

    func testPreserveUnicode() throws {
        let text = "안녕하세요 👋🏽"
        let envelope = Envelope(
            type: "clipboard.update",
            payload: .object(["kind": .string("text"), "body": .string(text), "ts": .number(1_762_752_004_000)])
        )

        let decoded = try MessageCodec.decode(try MessageCodec.encode(envelope))

        XCTAssertEqual(decoded.payload, envelope.payload)
        guard case .object(let payload) = decoded.payload else {
            return XCTFail("payload is not an object")
        }
        XCTAssertEqual(payload["body"], .string(text))
    }

    func testAcceptNonObjectPayload() throws {
        let json = #"{"v":1,"id":"x","type":"future.event","ts":1,"payload":[1,2,3]}"#
        let envelope = try MessageCodec.decode(Data(json.utf8))

        XCTAssertEqual(envelope.type, "future.event")
        XCTAssertEqual(envelope.payload, .array([.number(1), .number(2), .number(3)]))
    }

    func testDecodeIgnoresUnknownFieldsAndTypes() throws {
        let known = try MessageCodec.decode(try FixtureLoader.data("hello-unknown-field"))
        XCTAssertEqual(known.type, "hello")
        guard case .object(let payload) = known.payload else {
            return XCTFail("payload is not an object")
        }
        XCTAssertEqual(payload["deviceId"], .string("mac-7f3a9c"))

        let json = #"{"v":1,"id":"x","type":"future.event","ts":1,"payload":{}}"#
        XCTAssertEqual(try MessageCodec.decode(Data(json.utf8)).type, "future.event")
    }

    func testEncodeFillsDefaultsAndOmitsNilRe() throws {
        let before = Int64(Date().timeIntervalSince1970 * 1000)
        let envelope = Envelope(type: "ping")
        let after = Int64(Date().timeIntervalSince1970 * 1000)

        XCTAssertEqual(envelope.v, 1)
        XCTAssertFalse(envelope.id.isEmpty)
        XCTAssertGreaterThanOrEqual(envelope.ts, before)
        XCTAssertLessThanOrEqual(envelope.ts, after)

        let encoded = try MessageCodec.encode(envelope)
        let json = try XCTUnwrap(String(data: encoded, encoding: .utf8))
        XCTAssertFalse(json.contains("\"re\""))
    }
}
