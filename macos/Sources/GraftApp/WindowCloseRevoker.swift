import AppKit

/// Runs an action when a specific window closes.
///
/// SwiftUI's `onDisappear` does not fire when a `Window` scene's window is ordered out — the scene
/// keeps the window and its hosting view alive — so the pairing offer would stay valid until its
/// token expired. Observing `willCloseNotification` is the reliable hook.
///
/// The window is found by title rather than through a hosting-view accessor: a zero-size
/// `NSViewRepresentable` placed in `.background` is never attached to a window, so the accessor
/// silently never ran.
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

/// Finds the app's pairing window among the windows AppKit knows about.
enum PairingWindowLocator {
    static let title = "Pair with a phone"

    static func find(in windows: [NSWindow]) -> NSWindow? {
        windows.first { $0.title == title }
    }
}
