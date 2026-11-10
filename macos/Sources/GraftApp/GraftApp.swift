import AppKit
import SwiftUI
import GraftMacKit
import GraftProtocol

@main
struct GraftApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var service = GraftService.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent(service: service)
        } label: {
            Image(systemName: service.statusSymbol)
                .accessibilityLabel(service.menuState.statusLabel)
        }
        .menuBarExtraStyle(.window)
    }
}

/// Starts the server at launch.
///
/// A MenuBarExtra window is created lazily, so starting from the view's `task` would leave the
/// app idle until the user clicked the icon — the phone would never connect.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        GraftLauncher.shared.startIfNeeded()
    }
}

/// Small bridge so the delegate can reach the same service instance the scene observes.
@MainActor
final class GraftLauncher {
    static let shared = GraftLauncher()

    func startIfNeeded() {
        let service = GraftService.shared
        guard !service.hasStarted else { return }
        try? service.start()
        service.markStarted()
    }
}
