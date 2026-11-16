import AppKit
import SwiftUI

/// Runs an action when a specific window closes.
///
/// SwiftUI's `onDisappear` does not fire when a `Window` scene's window is ordered out — the scene
/// keeps the window and its hosting view alive — so the pairing offer would stay valid until its
/// token expired. Observing `willCloseNotification` is the reliable hook.
final class WindowCloseRevoker {
    private var observer: NSObjectProtocol?

    init(window: NSWindow, action: @escaping () -> Void) {
        observer = NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification,
            object: window,
            queue: .main
        ) { _ in
            action()
        }
    }

    deinit {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }
}

/// Reaches the AppKit window behind a SwiftUI view so it can be observed and ordered front.
struct WindowAccessor: NSViewRepresentable {
    let onWindow: (NSWindow) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            if let window = view.window { onWindow(window) }
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}
