import SwiftUI
import GraftMacKit

/// The pairing offer in its own window: a QR code, or the three values to type.
///
/// Closing the window revokes the token, so an offer never outlives the window that showed it.
struct PairingWindowView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(spacing: 16) {
            if let manual = service.manualPairing {
                manualValues(manual)
            } else if let image = service.qrImage {
                qr(image)
            } else {
                ProgressView("Preparing the pairing offer…")
            }

            Divider()

            if service.manualPairing == nil {
                Button("No camera? Use a code instead") {
                    service.beginManualPairing()
                }
                .buttonStyle(.link)
            } else {
                Button("Use the QR code instead") {
                    service.beginPairing()
                }
                .buttonStyle(.link)
            }

            Text("The offer closes when this window closes.")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
        }
        .padding(24)
        .frame(minWidth: 420, minHeight: 560)
        .background(Palette.background)
        .onDisappear { service.cancelPairing() }
    }

    private func qr(_ image: NSImage) -> some View {
        VStack(spacing: 12) {
            Text("Scan with your phone")
                .font(.headline)
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 260, height: 260)
                .background(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            Text("Open Graft on the phone and point it at this code.")
                .font(.callout)
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
    }

    private func manualValues(_ manual: PairingService.NumericPairing) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Type these on your phone")
                .font(.headline)

            row(label: "Code", value: grouped(manual.code), monospaced: true, size: 24)
            row(label: "Fingerprint", value: manual.fingerprintPrefix, monospaced: true, size: 15)
            row(label: "Address", value: "\(manual.host):\(manual.port)", monospaced: true, size: 15)

            Button("Copy details") {
                let pasteboard = NSPasteboard.general
                pasteboard.clearContents()
                pasteboard.setString(
                    "\(manual.code)\n\(manual.fingerprintPrefix)\n\(manual.host):\(manual.port)",
                    forType: .string
                )
            }

            Text("The fingerprint must match what the phone shows before it trusts this Mac.")
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(label: String, value: String, monospaced: Bool, size: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Palette.onSurfaceVariant)
            Text(value)
                .font(monospaced ? .system(size: size, design: .monospaced) : .system(size: size))
                .textSelection(.enabled)
        }
    }

    private func grouped(_ code: String) -> String {
        guard code.count == 6 else { return code }
        let middle = code.index(code.startIndex, offsetBy: 3)
        return "\(code[..<middle]) \(code[middle...])"
    }
}
