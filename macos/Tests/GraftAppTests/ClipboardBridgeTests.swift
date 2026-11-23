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

    func testEmptyClipboardProducesNoEnvelopeAndSaysWhy() {
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: FakeReader(value: nil))

        XCTAssertNil(bridge.outgoing())
        XCTAssertEqual(bridge.lastError, "There is no text on the clipboard")
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

    private final class MutableReader: ClipboardReading {
        var value: String?
        var pngData: Data?
        var changeCount: Int = 1
        var isConcealed: Bool = false
        func read() -> String? { value }
        func readImagePNG() -> Data? { pngData }
    }

    func testPollOutgoingEmitsOnlyWhenChangeCountMovesAndSkipsConcealed() throws {
        let reader = MutableReader()
        reader.value = "initial"
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: reader)

        // Already on the clipboard at startup -> must not send.
        XCTAssertNil(bridge.pollOutgoing())

        // Concealed password manager item -> skipped even when changeCount advances.
        reader.value = "super-secret"
        reader.isConcealed = true
        reader.changeCount += 1
        XCTAssertNil(bridge.pollOutgoing())

        // Normal user copy -> emitted once.
        reader.value = "copied on mac"
        reader.isConcealed = false
        reader.changeCount += 1
        let envelope = try XCTUnwrap(bridge.pollOutgoing())
        XCTAssertEqual(envelope.payload.objectValue?["body"]?.stringValue, "copied on mac")
        XCTAssertNil(bridge.pollOutgoing())
    }

    func testReceivedClipboardDoesNotEchoBackOnPoll() {
        let reader = MutableReader()
        let writer = FakeWriter()
        let bridge = ClipboardBridge(writer: writer, reader: reader)

        XCTAssertTrue(bridge.received(textUpdate("from android")))
        reader.value = "from android"
        reader.changeCount += 1

        XCTAssertNil(bridge.pollOutgoing(), "inbound peer text must not echo back")
    }

    func testOutgoingImageReturnsPngUnlessConcealed() {
        let reader = MutableReader()
        reader.pngData = Data([0x89, 0x50, 0x4E, 0x47])
        let bridge = ClipboardBridge(writer: FakeWriter(), reader: reader)

        XCTAssertEqual(bridge.outgoingImagePNG(), reader.pngData)

        reader.isConcealed = true
        XCTAssertNil(bridge.outgoingImagePNG())
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
