import Foundation
import GraftProtocol

protocol ClipboardWriting {
    func write(_ text: String)
}

protocol ClipboardReading {
    func read() -> String?
}

/// Moves clipboard text between the connection and the system pasteboard.
///
/// Android 10+ blocks background clipboard access, so this type deliberately does no polling: the
/// app writes what arrives and sends only when the user asks.
final class ClipboardBridge {
    static let maxBytes = 256 * 1024

    private let writer: ClipboardWriting
    private let reader: ClipboardReading
    private(set) var lastError: String?

    init(writer: ClipboardWriting, reader: ClipboardReading) {
        self.writer = writer
        self.reader = reader
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

        writer.write(body)
        lastError = nil
        return true
    }

    /// Builds an envelope for the current clipboard, or nil when there is no text.
    func outgoing(now: Date = Date()) -> Envelope? {
        guard let text = reader.read(), !text.isEmpty else { return nil }
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
