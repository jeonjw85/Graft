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
        VStack(alignment: .leading, spacing: 10) {
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
                HStack(spacing: 8) {
                    Image(systemName: "qrcode")
                        .font(.caption)
                        .foregroundStyle(Palette.warning)
                    Text("Pairing window is open")
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                    Spacer()
                    Button("Show window") { showPairingWindow() }
                        .buttonStyle(.link)
                        .font(.caption.weight(.medium))
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(Palette.warning.opacity(0.35), lineWidth: 1)
                )
            }

            peerList

            if service.menuState.isConnected || !service.transfers.isEmpty {
                actions
            }

            Rectangle()
                .fill(Palette.outline.opacity(0.7))
                .frame(height: 1)
                .padding(.top, 2)

            footer
        }
        .padding(14)
        .frame(width: 332)
        .background(Palette.background)
        .overlay {
            if isDropTargeted && service.menuState.isConnected {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(Palette.primary, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
                    .background(Palette.background.opacity(0.94), in: RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        VStack(spacing: 8) {
                            Image(systemName: "arrow.up.doc.fill")
                                .font(.system(size: 26))
                                .foregroundStyle(Palette.primary)
                            Text("Drop files or folders to send")
                                .font(.callout.weight(.semibold))
                                .foregroundStyle(Palette.onSurface)
                        }
                    }
                    .padding(8)
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
        let tone = Palette.tone(for: service.menuState.status)
        let accent = Palette.color(for: tone)
        let borderColor = tone == .neutral ? Palette.outline : accent.opacity(0.35)

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .fill(accent.opacity(0.18))
                        .frame(width: 22, height: 22)
                    Circle()
                        .fill(accent)
                        .frame(width: 8, height: 8)
                }

                Text(localizedTitle)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(Palette.onSurface)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer(minLength: 4)

                if service.menuState.isConnected, let status = service.peerStatus {
                    HStack(spacing: 4) {
                        Image(systemName: status.batterySymbol)
                            .font(.system(size: 11))
                            .foregroundStyle(
                                status.battery <= 20 && !status.charging
                                    ? Palette.warning
                                    : (status.charging ? Palette.connected : Palette.onSurfaceVariant)
                            )
                        Text("\(status.battery)%")
                            .font(.caption2.weight(.medium).monospacedDigit())
                            .foregroundStyle(Palette.onSurface)
                    }
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Palette.surfaceVariant, in: Capsule())
                    .overlay(Capsule().strokeBorder(Palette.outline, lineWidth: 1))
                    .accessibilityLabel("Phone battery \(status.battery) percent\(status.charging ? ", charging" : "")")
                }
            }

            if let detail = summary.detail {
                Text(LocalizedStringKey(detail))
                    .font(.caption)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .textSelection(.enabled)
                    .padding(.leading, 32)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(borderColor, lineWidth: 1)
        )
    }

    /// Healing actions for a failed start: a retry (port freed) and the privacy pane (local network).
    private var errorActions: some View {
        HStack(spacing: 8) {
            PopoverPrimaryButton(title: "Try again", systemImage: "arrow.clockwise") {
                service.restart()
            }
            PopoverFooterButton(title: "Privacy Settings", systemImage: "lock.shield") {
                service.openLocalNetworkSettings()
            }
        }
    }

    /// Pairing starts with the QR code; the typed values live inside the window.
    private var pairingActions: some View {
        PopoverPrimaryButton(title: "Pair a phone", systemImage: "qrcode") {
            service.beginPairing()
            showPairingWindow()
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                PopoverActionTile(
                    title: "Send files…",
                    systemImage: "arrow.up.doc.fill",
                    accent: Palette.primary,
                    emphasized: true,
                    disabled: !service.menuState.isConnected
                ) {
                    chooseFiles()
                }

                PopoverActionTile(
                    title: "Send clipboard",
                    systemImage: "doc.on.clipboard",
                    accent: Palette.onSurface,
                    emphasized: false,
                    disabled: !service.menuState.isConnected
                ) {
                    service.sendClipboard()
                }

                if service.menuState.isConnected {
                    PopoverActionTile(
                        title: service.isRingingPeer ? "Stop ringing" : "Ring phone",
                        systemImage: service.isRingingPeer ? "bell.slash.fill" : "bell.and.waves.left.and.right",
                        accent: service.isRingingPeer ? Palette.warning : Palette.onSurface,
                        emphasized: service.isRingingPeer,
                        disabled: false
                    ) {
                        service.toggleRingPeer()
                    }
                }
            }

            if service.menuState.isConnected && service.transfers.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.down.doc")
                        .font(.caption2)
                        .foregroundStyle(Palette.onSurfaceVariant.opacity(0.85))
                    Text("Tip: drop files or folders here to send.")
                        .font(.caption2)
                        .foregroundStyle(Palette.onSurfaceVariant)
                    Spacer()
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(Palette.surface.opacity(0.7), in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(Palette.outline.opacity(0.85), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                )
            }

            let summary = TransferSummary.from(
                transfers: service.transfers,
                finished: [],
                failure: service.transferFailure
            )
            ForEach(summary.rows, id: \.id) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Image(systemName: row.progress.direction == .send ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                            .font(.caption)
                            .foregroundStyle(Palette.primary)
                        Text(row.progress.name)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Palette.onSurface)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Text("\(percent(row.progress))%")
                            .font(.caption2.weight(.medium).monospacedDigit())
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
                    GeometryReader { geo in
                        let fraction = CGFloat(min(max(row.progress.fraction, 0), 1))
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(Palette.surfaceVariant)
                            Capsule()
                                .fill(Palette.primary)
                                .frame(width: max(4, geo.size.width * fraction))
                        }
                    }
                    .frame(height: 5)
                    if let metric = service.transferMetrics[row.id] {
                        Text(metric.label)
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(Palette.onSurfaceVariant)
                    }
                }
                .padding(10)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(Palette.outline, lineWidth: 1)
                )
            }

            if let failure = service.transferFailure {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(Palette.error)
                    Text(LocalizedStringKey(failure))
                        .font(.caption)
                        .foregroundStyle(Palette.error)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.error.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(Palette.error.opacity(0.3), lineWidth: 1)
                )
            }
        }
    }

    private var peerList: some View {
        VStack(alignment: .leading, spacing: 6) {
            if service.peers.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "iphone.slash")
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                    Text("No paired phones yet.")
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(Palette.outline, lineWidth: 1)
                )
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(service.peers.enumerated()), id: \.element.fingerprint) { index, peer in
                        if index > 0 {
                            Rectangle()
                                .fill(Palette.outline.opacity(0.65))
                                .frame(height: 1)
                                .padding(.horizontal, 10)
                        }
                        HStack(spacing: 8) {
                            Image(systemName: "iphone")
                                .font(.caption)
                                .foregroundStyle(Palette.onSurfaceVariant)
                            Text(peer.name)
                                .font(.callout.weight(.medium))
                                .foregroundStyle(Palette.onSurface)
                                .lineLimit(1)
                            Spacer()
                            Menu {
                                Button("Forget", role: .destructive) {
                                    service.forgetPeer(fingerprint: peer.fingerprint)
                                }
                            } label: {
                                Image(systemName: "ellipsis")
                                    .foregroundStyle(Palette.onSurfaceVariant)
                                    .frame(width: 24, height: 22)
                                    .contentShape(Rectangle())
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                            .accessibilityLabel("Actions for \(peer.name)")
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                    }
                }
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(Palette.outline, lineWidth: 1)
                )
            }
        }
    }

    private var footer: some View {
        HStack {
            if #available(macOS 14, *) {
                // `showSettingsWindow:` stopped working on newer macOS; SettingsLink is the
                // supported way and is a button in the popover.
                SettingsLink {
                    PopoverFooterLabel(title: "Settings…", systemImage: "gearshape")
                }
                .buttonStyle(.plain)
            } else {
                PopoverFooterButton(title: "Settings…", systemImage: "gearshape") {
                    openSettings()
                }
            }
            Spacer()
            PopoverFooterButton(title: "Quit", systemImage: "power") {
                NSApplication.shared.terminate(nil)
            }
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

/// Square quick-action tile with icon, label, and subtle hover feedback.
private struct PopoverActionTile: View {
    let title: LocalizedStringKey
    let systemImage: String
    let accent: Color
    let emphasized: Bool
    let disabled: Bool
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        let fill: Color = {
            if emphasized {
                return accent.opacity(isHovered && !disabled ? 0.24 : 0.16)
            }
            return isHovered && !disabled ? Palette.surfaceVariant : Palette.surface
        }()
        let border = emphasized ? accent.opacity(0.45) : (isHovered && !disabled ? Palette.outline.opacity(1.2) : Palette.outline)

        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(disabled ? Palette.onSurfaceVariant.opacity(0.45) : accent)
                    .frame(height: 18)
                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(disabled ? Palette.onSurfaceVariant.opacity(0.5) : Palette.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .background(fill, in: RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(border, lineWidth: 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .onHover { isHovered = $0 }
    }
}

/// Full-width primary action pill for pairing or recovery actions.
private struct PopoverPrimaryButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 12.5, weight: .semibold))
                Text(title)
                    .font(.system(size: 12.5, weight: .semibold))
            }
            .foregroundStyle(Color(red: 11 / 255, green: 16 / 255, blue: 32 / 255))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(
                Palette.primary.opacity(isHovered ? 0.92 : 1.0),
                in: RoundedRectangle(cornerRadius: 10)
            )
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

private struct PopoverFooterLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    @State private var isHovered = false

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: systemImage)
                .font(.system(size: 11.5))
            Text(title)
                .font(.caption.weight(.medium))
        }
        .foregroundStyle(isHovered ? Palette.onSurface : Palette.onSurfaceVariant)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(
            isHovered ? Palette.surfaceVariant : Color.clear,
            in: RoundedRectangle(cornerRadius: 7)
        )
        .contentShape(RoundedRectangle(cornerRadius: 7))
        .onHover { isHovered = $0 }
    }
}

private struct PopoverFooterButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            PopoverFooterLabel(title: title, systemImage: systemImage)
        }
        .buttonStyle(.plain)
    }
}
