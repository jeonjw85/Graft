import AppKit
import SwiftUI
import UserNotifications
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

        Window("Pair with a phone", id: "pairing") {
            PairingWindowView(service: service)
        }
        .defaultSize(width: 420, height: 560)

        Settings {
            SettingsWindowView(service: service)
        }
    }
}

/// Starts the server at launch.
///
/// A MenuBarExtra window is created lazily, so starting from the view's `task` would leave the
/// app idle until the user clicked the icon — the phone would never connect.
final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Dark only by design; the menu bar icon keeps system colouring.
        NSApp.appearance = NSAppearance(named: .darkAqua)
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        SystemNotificationCenter.registerCategories()
        GraftLauncher.shared.startIfNeeded()
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let key = response.notification.request.identifier
        let actionId = response.actionIdentifier
        let replyText = (response as? UNTextInputNotificationResponse)?.userText
        Task { @MainActor in
            if actionId == SystemNotificationCenter.replyActionId, let replyText, !replyText.isEmpty {
                GraftService.shared.sendNotificationReply(key: key, text: replyText)
            } else if actionId == UNNotificationDismissActionIdentifier {
                GraftService.shared.sendNotificationDismiss(key: key)
            }
        }
        completionHandler()
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
        service.requestNotificationAuthorization()
    }
}
