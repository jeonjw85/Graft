import SwiftUI

struct MenuContent: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
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
