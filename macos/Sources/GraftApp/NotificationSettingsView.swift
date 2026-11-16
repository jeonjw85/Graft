import SwiftUI

/// Notification display preferences on the Mac.
struct NotificationSettingsView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
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
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
