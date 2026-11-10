import AppKit
import GraftMacKit
import GraftProtocol
import SwiftUI

/// Lists active transfers and offers a file picker.
struct TransferProgressView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Button("Send a file…") {
                    let panel = NSOpenPanel()
                    panel.canChooseFiles = true
                    panel.canChooseDirectories = false
                    panel.allowsMultipleSelection = false
                    if panel.runModal() == .OK, let url = panel.url {
                        service.sendFile(url: url)
                    }
                }
                .disabled(!service.menuState.isConnected)

                Spacer()
            }

            if let failure = service.transferFailure {
                Text(failure)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            ForEach(sortedTransfers, id: \.id) { item in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Image(systemName: item.progress.direction == .send
                              ? "arrow.up.circle"
                              : "arrow.down.circle")
                            .foregroundStyle(.secondary)
                        Text(item.progress.name)
                            .font(.caption)
                            .lineLimit(1)
                        Spacer()
                        Text("\(Int(item.progress.fraction * 100))%")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    ProgressView(value: item.progress.fraction)
                        .progressViewStyle(.linear)
                }
            }
        }
    }

    private var sortedTransfers: [(id: UInt32, progress: TransferProgress)] {
        service.transfers
            .map { (id: $0.key, progress: $0.value) }
            .sorted { $0.id < $1.id }
    }
}
