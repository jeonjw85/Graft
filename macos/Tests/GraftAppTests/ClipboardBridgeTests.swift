import XCTest
@testable import GraftApp
import GraftProtocol

final class ClipboardBridgeTests: XCTestCase {
    private final class FakeWriter: ClipboardWriting {
        var text: String?
        func write(_ text: String) { self.text = text }
    }

    private struct FakeReader: ClipboardReading {
        let value: String?
        func read() -> String? { value }
    }

    private func textUpdate(_ body: String, kind: String = "text") -> Envelope {
        Envelope(
            type: "clipboard.update",
            payload: .object([
                "kind": .string(kind),
                "body": .string(body),
                "ts": .number(1)
            ])
        )
    }

    func testReceivedClipboardTextIsWritten() {
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: FakeReader(value: nil))

        XCTAssertTrue(bridge.received(textUpdate("안녕하세요 👋🏽")))
        XCTAssertEqual(writer.text, "안녕하세요 👋🏽")
    }

    func testNonTextKindIsIgnored() {
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: FakeReader(value: nil))

        XCTAssertFalse(bridge.received(textUpdate("iVBORw0KGgo=", kind: "image")))
        XCTAssertNil(writer.text)
    }

    func testOtherMessageTypesAreIgnored() {
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: FakeReader(value: nil))

        XCTAssertFalse(bridge.received(Envelope(type: "hello", payload: .object([:]))))
        XCTAssertNil(writer.text)
    }

    func testOversizedTextIsRejected() {
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: FakeReader(value: nil))
        let huge = String(repeating: "a", count: 300 * 1024)

        XCTAssertFalse(bridge.received(textUpdate(huge)))
        XCTAssertNil(writer.text)
        XCTAssertNotNil(bridge.lastError)
    }

    func testEmptyClipboardProducesNoEnvelope() {
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: FakeReader(value: nil))
        XCTAssertNil(bridge.outgoing())
    }

    func testOutgoingCarriesKindBodyAndTimestamp() throws {
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: FakeReader(value: "hello"))

        let envelope = try XCTUnwrap(bridge.outgoing(now: Date(timeIntervalSince1970: 1_762_752_000)))
        let payload = try XCTUnwrap(envelope.payload.objectValue)

        XCTAssertEqual(envelope.type, "clipboard.update")
        XCTAssertEqual(payload["kind"]?.stringValue, "text")
        XCTAssertEqual(payload["body"]?.stringValue, "hello")
        XCTAssertEqual(payload["ts"]?.numberValue, 1_762_752_000_000)
    }

    func testOutgoingRejectsOversizedClipboard() {
        let huge = String(repeating: "b", count: 300 * 1024)
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: FakeReader(value: huge))

        XCTAssertNil(bridge.outgoing())
        XCTAssertNotNil(bridge.lastError)
    }

    func testEmptyStringClipboardIsNotSent() {
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: FakeReader(value: ""))
        XCTAssertNil(bridge.outgoing())
    }
}

extension JSONValue {
    var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var numberValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }
}
