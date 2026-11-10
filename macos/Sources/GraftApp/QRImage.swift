import AppKit
import GraftProtocol
import CoreImage
import CoreImage.CIFilterBuiltins

/// Renders a pairing URI as a QR code.
enum QRImage {
    static func make(from string: String, scale: CGFloat = 8) -> NSImage? {
        // A QR code is produced for almost any input, so reject anything that is not a pairing URI
        // first. Otherwise a bug that passes an empty or unrelated string still renders a scannable
        // code that leads nowhere.
        guard (try? PairingURI.parse(string)) != nil else { return nil }
        guard let data = string.data(using: .utf8) else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"

        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}
