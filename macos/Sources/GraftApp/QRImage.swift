import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Renders a pairing URI as a QR code.
enum QRImage {
    static func make(from string: String, scale: CGFloat = 8) -> NSImage? {
        guard let data = string.data(using: .utf8), !data.isEmpty else { return nil }

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
