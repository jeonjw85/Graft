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

            if case .failed = service.menuState.status {
                errorActions
            }

            if service.menuState.canPair {
                pairingActions
            } else if service.menuState.status == .pairing {
                // A window is already open; starting again would invalidate the code on screen.
                HStack {
                    Text("Pairing window is open")
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                    Spacer()
                    Button("Show window") { showPairingWindow() }
                        .buttonStyle(.link)
                }
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

        }
    }

    /// Pairing starts with the QR code; the typed values live inside the window.
    /// Healing actions for a failed start: a retry (port freed) and the privacy pane (local network).
    private var errorActions: some View {
        HStack(spacing: 8) {
            Button("Try again") { service.restart() }
            Button("Privacy Settings") { service.openLocalNetworkSettings() }
                .buttonStyle(.link)
        }
    }

    private var pairingActions: some View {
        Button("Pair a phone") {
            service.beginPairing()
            showPairingWindow()
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

            let summary = TransferSummary.from(
                transfers: service.transfers,
                finished: [],
                failure: service.transferFailure
            )
            ForEach(summary.rows, id: \.id) { row in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(row.progress.name).font(.caption)
                        Spacer()
                        Text("\(percent(row.progress))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                    ProgressView(value: min(max(row.progress.fraction, 0), 1))
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
            if #available(macOS 14, *) {
                // `showSettingsWindow:` stopped working on newer macOS; SettingsLink is the
                // supported way and is a button in the popover.
                SettingsLink { Text("Settings…") }
            } else {
                Button("Settings…") { openSettings() }
            }
            Spacer()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
    }

    private func percent(_ progress: TransferProgress) -> Int {
        progress.total == 0 ? 100 : Int(min(max(progress.fraction, 0), 1) * 100)
    }

    private func chooseFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        _ = service.sendFile(url: url)
    }

    /// The macOS 13 fallback; newer systems use `SettingsLink`.
    private func openSettings() {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
    }

    /// A menu bar app is not active by default, so a window opened from the popover can land behind
    /// the front app. Activate before showing it.
    private func showPairingWindow() {
        NSApp.activate(ignoringOtherApps: true)
        openWindow(id: "pairing")
    }
}
