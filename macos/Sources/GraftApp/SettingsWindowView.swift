import SwiftUI
import GraftMacKit

/// The four settings pages.
///
/// Each page is a grouped `Form`, so rows sit in cards with native insets and the window matches
/// System Settings instead of stacking loose controls against the top edge.
struct SettingsWindowView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        TabView {
            NotificationsSettingsPage(service: service)
                .tabItem { Label("Notifications", systemImage: "bell") }
            ClipboardSettingsPage(service: service)
                .tabItem { Label("Clipboard", systemImage: "doc.on.clipboard") }
            TransfersSettingsPage(service: service)
                .tabItem { Label("Transfers", systemImage: "arrow.up.arrow.down") }
            AboutSettingsPage(service: service)
                .tabItem { Label("About", systemImage: "info.circle") }
        }
        .frame(width: 520, height: 480)
    }
}

struct NotificationsSettingsPage: View {
    @ObservedObject var service: GraftService

    /// Nil means the system has not been asked yet. That is a call to action, not a green tick.
    private var access: (symbol: String, tint: Color, needsAction: Bool) {
        switch service.notificationsAllowed {
        case .some(true):
            return ("checkmark.circle.fill", Palette.connected, false)
        case .some(false):
            return ("exclamationmark.triangle.fill", Palette.warning, true)
        case .none:
            return ("minus.circle", Palette.onSurfaceVariant, true)
        }
    }

    var body: some View {
        Form {
            Section {
                Toggle(
                    "Show notification previews",
                    isOn: Binding(
                        get: { service.notificationPreviews },
                        set: { service.updateNotificationPreview(enabled: $0) }
                    )
                )

                LabeledContent("Access") {
                    HStack(spacing: 6) {
                        Image(systemName: access.symbol)
                            .foregroundStyle(access.tint)
                        Text(LocalizedStringKey(service.notificationStatus))
                            .foregroundStyle(.secondary)
                    }
                }

                if access.needsAction {
                    Button("Open Notification Settings") { service.openNotificationSettings() }
                }
            } footer: {
                Text("When previews are off, alerts still arrive without their text.")
            }

            Section {
                if service.recentNotifications.isEmpty {
                    Text("Nothing forwarded yet.")
                        .foregroundStyle(.secondary)
                } else {
                    // Alerts can arrive with no timestamp; index them so identity stays unique.
                    ForEach(
                        Array(service.recentNotifications.prefix(20).enumerated()),
                        id: \.offset
                    ) { _, item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.app)
                                .font(.caption.weight(.semibold))
                            Text(item.title.isEmpty ? item.body : "\(item.title) — \(item.body)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Recent alerts")
                    Spacer()
                    Button("Clear") { service.clearRecentNotifications() }
                        .buttonStyle(.link)
                        .disabled(service.recentNotifications.isEmpty)
                }
            }
        }
        .formStyle(.grouped)
    }
}

struct ClipboardSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        Form {
            Section {
                Toggle("Share Mac clipboard automatically", isOn: $service.autoShareClipboard)
                Toggle("Copy received clipboard automatically", isOn: $service.autoCopyClipboard)
            } footer: {
                Text("New text copied on this Mac is sent to your phone automatically (concealed password items are skipped). Text from the phone replaces your clipboard as soon as it arrives.")
            }

            Section("Last sent") {
                if let last = service.lastSharedClipboard {
                    Text(last)
                        .textSelection(.enabled)
                        .lineLimit(6)
                } else {
                    Text("Nothing sent yet.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
    }
}

struct TransfersSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        Form {
            Section("Save received files to") {
                HStack(spacing: 8) {
                    Text(service.receiveDirectory.path)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                    Spacer()
                    Button("Choose…") { chooseReceiveDirectory() }
                    Button("Reveal") { service.revealReceiveDirectory() }
                }
            }

            Section {
                Toggle("Send new Mac screenshots automatically", isOn: $service.autoSendScreenshots)
            } footer: {
                Text("Watches \(ScreenshotWatcher.defaultScreenshotDirectory().path) for new macOS screenshots while connected.")
            }

            Section {
                if service.transferHistory.isEmpty {
                    Text("No transfers yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(service.transferHistory, id: \.self) { record in
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(record.name)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Text(
                                    "\(NSLocalizedString(TransferHistory.describe(record.direction), comment: ""))  ·  " +
                                    "\(Self.size(record.bytes))  ·  " +
                                    NSLocalizedString(TransferHistory.describe(record.failure), comment: "")
                                )
                                .font(.caption)
                                .foregroundStyle(record.failure == nil ? Palette.onSurfaceVariant : Palette.warning)
                            }
                            Spacer()
                            if let path = record.destinationPath,
                               record.failure == nil,
                               FileManager.default.fileExists(atPath: path) {
                                Button("Reveal") { service.revealFile(atPath: path) }
                                    .buttonStyle(.link)
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text("History")
                    Spacer()
                    Button("Clear") { service.clearTransferHistory() }
                        .buttonStyle(.link)
                        .disabled(service.transferHistory.isEmpty)
                }
            }
        }
        .formStyle(.grouped)
    }

    private func chooseReceiveDirectory() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        panel.directoryURL = service.receiveDirectory
        guard panel.runModal() == .OK, let url = panel.url else { return }
        service.updateReceiveDirectory(url)
    }

    static func size(_ bytes: UInt64) -> String {
        let units = ["B", "KiB", "MiB", "GiB"]
        var value = Double(bytes)
        var unit = 0
        while value >= 1024 && unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        return unit == 0 ? "\(bytes) B" : String(format: "%.1f %@", value, units[unit])
    }
}

struct AboutSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        Form {
            Section("Startup") {
                Toggle(
                    "Launch Graft at login",
                    isOn: Binding(
                        get: { service.launchAtLogin },
                        set: { service.setLaunchAtLogin($0) }
                    )
                )
            }

            Section {
                LabeledContent("Version", value: service.versionText)
                LabeledContent("Protocol", value: "1")
            }

            Section("Certificate fingerprint") {
                Text(service.localFingerprint.isEmpty ? "Unavailable" : service.localFingerprint)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)

                Button("Copy fingerprint") {
                    let pasteboard = NSPasteboard.general
                    pasteboard.clearContents()
                    pasteboard.setString(service.localFingerprint, forType: .string)
                }
                .disabled(service.localFingerprint.isEmpty)
            }

            Section("Links") {
                Link("Source code on GitHub", destination: URL(string: "https://github.com/jeonjw85/Graft")!)
                Link("Privacy policy", destination: URL(string: "https://jjw.kr/policy/graft")!)
            }
        }
        .formStyle(.grouped)
    }
}
