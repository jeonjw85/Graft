import Foundation
import GraftProtocol

protocol ClipboardWriting {
    func write(_ text: String)
}

protocol ClipboardReading {
    func read() -> String?
    func readImagePNG() -> Data?
    var changeCount: Int { get }
    var isConcealed: Bool { get }
}

extension ClipboardReading {
    func readImagePNG() -> Data? { nil }
    var changeCount: Int { 0 }
    var isConcealed: Bool { false }
}

/// Moves clipboard text between the connection and the system pasteboard.
///
/// Supports both explicit sends and optional `changeCount` polling for Mac -> Android automatic
/// sync while skipping concealed password-manager items and avoiding echo loops.
final class ClipboardBridge {
    static let maxBytes = 256 * 1024

    private let writer: ClipboardWriting
    private let reader: ClipboardReading
    private var lastChangeCount: Int
    private var lastReceivedText: String?
    private(set) var lastError: String?

    init(writer: ClipboardWriting, reader: ClipboardReading) {
        self.writer = writer
        self.reader = reader
        self.lastChangeCount = reader.changeCount
    }

    /// Applies an inbound frame. Returns whether anything was written.
    @discardableResult
    func received(_ envelope: Envelope) -> Bool {
        guard envelope.type == "clipboard.update" else { return false }
        guard case .object(let payload) = envelope.payload else { return false }
        guard payload["kind"]?.stringValue == "text" else { return false }
        guard let body = payload["body"]?.stringValue else { return false }

        guard body.utf8.count <= Self.maxBytes else {
            lastError = "Clipboard text is larger than 256 KiB and was not copied"
            return false
        }

        lastReceivedText = body
        writer.write(body)
        // Advance the counter past our own write so polling never echoes the peer's text back.
        lastChangeCount = reader.changeCount
        lastError = nil
        return true
    }

    /// Returns a new envelope when the local pasteboard changed to shareable text, or nil otherwise.
    func pollOutgoing(now: Date = Date()) -> Envelope? {
        let current = reader.changeCount
        guard current != lastChangeCount else { return nil }
        lastChangeCount = current

        if reader.isConcealed { return nil }
        guard let text = reader.read(), !text.isEmpty else { return nil }
        guard text.utf8.count <= Self.maxBytes else { return nil }
        if text == lastReceivedText {
            lastReceivedText = nil
            return nil
        }
        lastReceivedText = nil

        return Envelope(
            type: "clipboard.update",
            payload: .object([
                "kind": .string("text"),
                "body": .string(text),
                "ts": .number(now.timeIntervalSince1970 * 1000)
            ])
        )
    }

    /// Resets the observed pasteboard counter without sending the current clipboard.
    func syncChangeCount() {
        lastChangeCount = reader.changeCount
    }

    /// Returns PNG bytes when the local pasteboard holds an image instead of text.
    func outgoingImagePNG() -> Data? {
        guard !reader.isConcealed else { return nil }
        return reader.readImagePNG()
    }

    /// Builds an envelope for the current clipboard, or nil when there is no text.
    func outgoing(now: Date = Date()) -> Envelope? {
        guard let text = reader.read(), !text.isEmpty else {
            lastError = "There is no text on the clipboard"
            return nil
        }
        guard text.utf8.count <= Self.maxBytes else {
            lastError = "Clipboard text is larger than 256 KiB and was not sent"
            return nil
        }

        return Envelope(
            type: "clipboard.update",
            payload: .object([
                "kind": .string("text"),
                "body": .string(text),
                "ts": .number(now.timeIntervalSince1970 * 1000)
            ])
        )
    }
}

extension JSONValue {
    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }
}
