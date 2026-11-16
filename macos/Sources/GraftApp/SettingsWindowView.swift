import SwiftUI
import GraftMacKit

/// The four settings pages, sized for a window rather than a popover.
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
        .padding(20)
        .frame(width: 460, height: 420)
        .background(Palette.background)
    }
}

struct NotificationsSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(
                "Show notification previews",
                isOn: Binding(
                    get: { service.notificationPreviews },
                    set: { service.updateNotificationPreview(enabled: $0) }
                )
            )
            .toggleStyle(.checkbox)
            Text("When off, alerts still appear but without their text.")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)

            Text(service.notificationStatus)
                .font(.callout)
                .foregroundStyle(service.notificationsAllowed == false ? Palette.warning : Palette.onSurfaceVariant)

            if service.notificationsAllowed == false {
                Button("Open Notification Settings") { service.openNotificationSettings() }
            }

            Divider()

            HStack {
                Text("Recent alerts").font(.caption).foregroundStyle(Palette.onSurfaceVariant)
                Spacer()
                Button("Clear") { service.clearRecentNotifications() }
                    .disabled(service.recentNotifications.isEmpty)
            }

            if service.recentNotifications.isEmpty {
                Text("Nothing forwarded yet.")
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
            } else {
                List(service.recentNotifications.prefix(20), id: \.postedAt) { item in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(item.app).font(.caption.bold())
                        Text(item.title.isEmpty ? item.body : "\(item.title) — \(item.body)")
                            .font(.caption)
                            .foregroundStyle(Palette.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .frame(minHeight: 140)
            }
            Spacer()
        }
    }
}

struct ClipboardSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle("Copy received clipboard automatically", isOn: $service.autoCopyClipboard)
                .toggleStyle(.checkbox)
            Text("Only while the Mac app is in front; a text that arrived earlier is never pasted over what you copied since.")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)

            Divider()

            Text("Last sent").font(.caption).foregroundStyle(Palette.onSurfaceVariant)
            Text(service.lastSharedClipboard ?? "Nothing sent yet.")
                .font(.callout)
                .textSelection(.enabled)
                .lineLimit(4)
            Spacer()
        }
    }
}

struct TransfersSettingsPage: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(service.receiveDirectory.path)
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Button("Reveal in Finder") { service.revealReceiveDirectory() }
            }

            Divider()

            if service.transferHistory.isEmpty {
                Text("No transfers yet.")
                    .font(.callout)
                    .foregroundStyle(Palette.onSurfaceVariant)
            } else {
                List(service.transferHistory, id: \.finishedAt) { record in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(record.name).font(.callout)
                            Text(
                                "\(TransferHistory.describe(record.direction))  ·  " +
                                "\(Self.size(record.bytes))  ·  " +
                                TransferHistory.describe(record.failure)
                            )
                            .font(.caption)
                            .foregroundStyle(record.failure == nil ? Palette.onSurfaceVariant : Palette.warning)
                        }
                        Spacer()
                    }
                }
                .frame(minHeight: 200)
            }
            Spacer()
        }
    }

    static func size(_ bytes: UInt64) -> String {
        let units = ["B", "KB", "MiB", "GiB"]
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
        VStack(alignment: .leading, spacing: 10) {
            Text(service.versionText).font(.headline)
            Text("Protocol 1").font(.caption).foregroundStyle(Palette.onSurfaceVariant)

            Divider()

            Text("Certificate fingerprint").font(.caption).foregroundStyle(Palette.onSurfaceVariant)
            Text(service.localFingerprint)
                .font(.system(size: 12, design: .monospaced))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            Button("Copy fingerprint") {
                let pasteboard = NSPasteboard.general
                pasteboard.clearContents()
                pasteboard.setString(service.localFingerprint, forType: .string)
            }

            Divider()

            Link("github.com/jeonjw85/Graft", destination: URL(string: "https://github.com/jeonjw85/Graft")!)
            Spacer()
        }
    }
}
