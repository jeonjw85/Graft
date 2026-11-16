import SwiftUI

struct MenuContent: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Color.clear
                .frame(height: 0)
                .onAppear { GraftLauncher.shared.startIfNeeded() }

            header

            if let error = service.menuState.errorMessage {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if service.menuState.status == .pairing {
                PairingView(service: service)
            } else if service.menuState.canPair {
                Button("Pair a phone") {
                    service.beginPairing()
                }
            }

            peerList

            if service.menuState.isConnected {
                Divider()
                clipboardSection
                Divider()
                TransferProgressView(service: service)
            }

            if !service.recentNotifications.isEmpty {
                Divider()
                notificationsSection
            }

            Divider()

            HStack {
                Button("Quit") { NSApplication.shared.terminate(nil) }
                Spacer()
            }
        }
        .padding(16)
        .frame(width: 320)
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: service.statusSymbol)
                .foregroundStyle(service.statusColor)
            Text(service.menuState.statusLabel)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var notificationsSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Recent alerts")
                .font(.caption)
                .foregroundStyle(.secondary)
            ForEach(service.recentNotifications.prefix(5), id: \.postedAt) { item in
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.app).font(.caption.bold())
                    Text(item.title.isEmpty ? item.body : "\(item.title) — \(item.body)")
                        .font(.caption)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var clipboardSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button("Send clipboard") { service.sendClipboard() }
                Spacer()
                Toggle("Copy received", isOn: $service.autoCopyClipboard)
                    .toggleStyle(.checkbox)
            }
            if let last = service.lastSharedClipboard {
                Text("Last sent: \(last)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }

    private var peerList: some View {
        VStack(alignment: .leading, spacing: 6) {
            if service.peers.isEmpty {
                Text("No paired phones yet.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("Paired phones")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                ForEach(service.peers, id: \.fingerprint) { peer in
                    HStack {
                        Text(peer.name)
                        Spacer()
                        Button("Forget") {
                            service.forgetPeer(fingerprint: peer.fingerprint)
                        }
                        .buttonStyle(.link)
                    }
                }
            }
        }
    }
}
