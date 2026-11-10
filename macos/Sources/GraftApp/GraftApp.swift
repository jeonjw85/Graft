import SwiftUI
import GraftMacKit
import GraftProtocol

@main
struct GraftApp: App {
    @StateObject private var service = GraftService()

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
