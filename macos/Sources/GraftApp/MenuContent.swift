import SwiftUI
import UniformTypeIdentifiers
import GraftMacKit

/// The menu bar popover: the link state, then the three things a user does with it.
struct MenuContent: View {
    @ObservedObject var service: GraftService
    @Environment(\.openWindow) private var openWindow
    @State private var isDropTargeted = false

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
        .overlay {
            if isDropTargeted && service.menuState.isConnected {
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(Palette.primary, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
                    .background(Palette.background.opacity(0.92), in: RoundedRectangle(cornerRadius: 10))
                    .overlay {
                        VStack(spacing: 6) {
                            Image(systemName: "arrow.up.doc")
                                .font(.title2)
                                .foregroundStyle(Palette.primary)
                            Text("Drop files or folders to send")
                                .font(.callout.weight(.medium))
                        }
                    }
                    .padding(6)
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDrop(providers)
        }
    }

    private var localizedTitle: LocalizedStringKey {
        switch service.menuState.status {
        case .connected(let peer):
            return "Connected to \(peer)"
        case .idle:
            return "Not connected"
        case .searching:
            return "Looking for your phone…"
        case .pairing:
            return "Waiting for the phone to scan…"
        case .failed(let message):
            return LocalizedStringKey(message)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Palette.color(for: Palette.tone(for: service.menuState.status)))
                    .frame(width: 8, height: 8)
                Text(localizedTitle)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                if service.menuState.isConnected, let status = service.peerStatus {
                    HStack(spacing: 3) {
                        Image(systemName: status.batterySymbol)
                            .foregroundStyle(status.battery <= 20 && !status.charging ? Palette.warning : Palette.onSurfaceVariant)
                        Text("\(status.battery)%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                    .accessibilityLabel("Phone battery \(status.battery) percent\(status.charging ? ", charging" : "")")
                }
            }
            summary.detail.map {
                Text(LocalizedStringKey($0))
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
                Button("Send files…") { chooseFiles() }
                    .disabled(!service.menuState.isConnected)
                Spacer()
                Button("Send clipboard") { service.sendClipboard() }
                    .disabled(!service.menuState.isConnected)
            }

            if service.menuState.isConnected {
                Button {
                    service.toggleRingPeer()
                } label: {
                    Label(
                        service.isRingingPeer ? "Stop ringing" : "Ring phone",
                        systemImage: service.isRingingPeer ? "bell.slash" : "bell.and.waves.left.and.right"
                    )
                    .frame(maxWidth: .infinity)
                }
            }

            if service.menuState.isConnected && service.transfers.isEmpty {
                Text("Tip: drop files or folders here to send.")
                    .font(.caption2)
                    .foregroundStyle(Palette.onSurfaceVariant)
            }

            let summary = TransferSummary.from(
                transfers: service.transfers,
                finished: [],
                failure: service.transferFailure
            )
            ForEach(summary.rows, id: \.id) { row in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(row.progress.name)
                            .font(.caption)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text("\(percent(row.progress))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(Palette.onSurfaceVariant)
                        Button {
                            service.cancelTransfer(id: row.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(Palette.onSurfaceVariant)
                        }
                        .buttonStyle(.plain)
                        .help("Cancel transfer")
                        .accessibilityLabel("Cancel \(row.progress.name)")
                    }
                    ProgressView(value: min(max(row.progress.fraction, 0), 1))
                        .controlSize(.small)
                    if let metric = service.transferMetrics[row.id] {
                        Text(metric.label)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                }
            }

            if let failure = service.transferFailure {
                Text(LocalizedStringKey(failure))
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

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK, !panel.urls.isEmpty else { return }
        service.sendFiles(urls: panel.urls)
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard service.menuState.isConnected else { return false }
        let fileProviders = providers.filter { $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) }
        guard !fileProviders.isEmpty else { return false }

        for provider in fileProviders {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                let url: URL?
                if let data = item as? Data {
                    url = URL(dataRepresentation: data, relativeTo: nil)
                } else if let direct = item as? URL {
                    url = direct
                } else {
                    url = nil
                }
                guard let fileURL = url, fileURL.isFileURL else { return }
                Task { @MainActor in
                    _ = service.sendFile(url: fileURL)
                }
            }
        }
        return true
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
