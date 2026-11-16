import SwiftUI
import GraftMacKit

/// The menu bar popover: the link state, then the three things a user does with it.
struct MenuContent: View {
    @ObservedObject var service: GraftService
    @Environment(\.openWindow) private var openWindow

    private var summary: PopoverSummary {
        PopoverSummary.from(
            state: service.menuState,
            peerAddress: service.peerAddress,
            fingerprintPrefix: service.fingerprintPrefix
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Color.clear
                .frame(height: 0)
                .onAppear { GraftLauncher.shared.startIfNeeded() }

            header

            if service.menuState.canPair || service.menuState.status == .pairing {
                pairingActions
            }

            peerList

            if service.menuState.isConnected || !service.transfers.isEmpty {
                Divider()
                actions
            }

            Divider()
            footer
        }
        .padding(16)
        .frame(width: 320)
        .background(Palette.background)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Palette.color(for: Palette.tone(for: service.menuState.status)))
                    .frame(width: 8, height: 8)
                Text(summary.title)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
            }
            summary.detail.map {
                Text($0)
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .textSelection(.enabled)
            }
            if summary.showsError, let error = service.menuState.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(Palette.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var pairingActions: some View {
        HStack(spacing: 8) {
            Button("Pair a phone") {
                service.beginPairing()
                openWindow(id: "pairing")
            }
            Button("Pair without a camera") {
                service.beginManualPairing()
                openWindow(id: "pairing")
            }
            .buttonStyle(.link)
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button("Send a file…") { chooseFile() }
                    .disabled(!service.menuState.isConnected)
                Spacer()
                Button("Send clipboard") { service.sendClipboard() }
                    .disabled(!service.menuState.isConnected)
            }

            ForEach(Array(service.transfers.values).sorted { $0.name < $1.name }, id: \.name) { progress in
                VStack(alignment: .leading, spacing: 2) {
                    Text(progress.name)
                        .font(.caption)
                    ProgressView(value: min(max(progress.fraction, 0), 1))
                        .controlSize(.small)
                }
            }

            if let failure = service.transferFailure {
                Text(failure)
                    .font(.caption)
                    .foregroundStyle(Palette.error)
            }
        }
    }

    private var peerList: some View {
        VStack(alignment: .leading, spacing: 6) {
            if service.peers.isEmpty {
                Text("No paired phones yet.")
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
            } else {
                ForEach(service.peers, id: \.fingerprint) { peer in
                    HStack {
                        Text(peer.name)
                            .font(.callout)
                        Spacer()
                        Menu {
                            Button("Forget", role: .destructive) {
                                service.forgetPeer(fingerprint: peer.fingerprint)
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()
                        .accessibilityLabel("Actions for \(peer.name)")
                    }
                }
            }
        }
    }

    private var footer: some View {
        HStack {
            Button("Settings…") { openSettings() }
            Spacer()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
    }

    private func chooseFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        _ = service.sendFile(url: url)
    }

    /// `SettingsLink` is macOS 14+, and this app runs on 13.
    private func openSettings() {
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
    }
}
