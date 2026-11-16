import GraftMacKit
import SwiftUI

struct PairingView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let image = service.qrImage {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 180, height: 180)
            } else if service.manualPairing == nil {
                ProgressView()
                    .frame(width: 180, height: 180)
            }

            if let code = service.fallbackCode {
                Text("Or enter this code: \(PairingViewState(fallbackCode: code).displayCode)")
                    .font(.callout)
                    .textSelection(.enabled)
            }

            Text("The code expires in two minutes.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if let manual = service.manualPairing {
                Divider()
                manualSection(manual)
            }
        }
    }
}

extension PairingView {
    /// Values for a phone without a camera: code, fingerprint prefix, and address.
    @ViewBuilder
    func manualSection(_ manual: PairingService.NumericPairing) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("No camera? Enter these on the phone:")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Code  \(PairingViewState(fallbackCode: manual.code).displayCode)")
                .font(.title3.monospacedDigit())
                .textSelection(.enabled)
            Text("Fingerprint  \(manual.fingerprintPrefix)")
                .font(.callout.monospaced())
                .textSelection(.enabled)
            Text("Address  \(manual.host):\(manual.port)")
                .font(.callout.monospaced())
                .textSelection(.enabled)
            Text("The fingerprint must match what the phone shows before it trusts this Mac.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct PairingViewState {
    let fallbackCode: String

    var displayCode: String {
        guard fallbackCode.count == 6 else { return fallbackCode }
        let index = fallbackCode.index(fallbackCode.startIndex, offsetBy: 3)
        return "\(fallbackCode[..<index]) \(fallbackCode[index...])"
    }
}
