import AppKit

/// Bridges the system pasteboard to the clipboard bridge's protocols.
struct PasteboardWriter: ClipboardWriting {
    func write(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
    }
}

struct PasteboardReader: ClipboardReading {
    func read() -> String? {
        NSPasteboard.general.string(forType: .string)
    }
}
