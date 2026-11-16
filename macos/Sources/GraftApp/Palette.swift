import SwiftUI

/// How a status should read, independent of the colour that represents it.
enum PaletteTone: Equatable {
    case positive
    case neutral
    case warning
    case negative
}

/// The dark-only palette, shared with the Android app.
///
/// Colours are declared as hex so the token table in the spec and the drawn colour cannot drift; a
/// test pins every value.
enum Palette {
    static let backgroundHex: UInt32 = 0x0E1116
    static let surfaceHex: UInt32 = 0x151A21
    static let surfaceVariantHex: UInt32 = 0x1C232C
    static let primaryHex: UInt32 = 0x5B8CFF
    static let onSurfaceHex: UInt32 = 0xE6EAF0
    static let onSurfaceVariantHex: UInt32 = 0x9BA6B4
    static let outlineHex: UInt32 = 0x2A3240
    static let connectedHex: UInt32 = 0x35C46B
    static let warningHex: UInt32 = 0xF0A63C
    static let errorHex: UInt32 = 0xF2555A

    static let background = color(hex: backgroundHex)
    static let surface = color(hex: surfaceHex)
    static let surfaceVariant = color(hex: surfaceVariantHex)
    static let primary = color(hex: primaryHex)
    static let onSurface = color(hex: onSurfaceHex)
    static let onSurfaceVariant = color(hex: onSurfaceVariantHex)
    static let outline = color(hex: outlineHex)
    static let connected = color(hex: connectedHex)
    static let warning = color(hex: warningHex)
    static let error = color(hex: errorHex)

    static func color(for tone: PaletteTone) -> Color {
        switch tone {
        case .positive: return connected
        case .neutral: return onSurfaceVariant
        case .warning: return warning
        case .negative: return error
        }
    }

    static func tone(for status: ConnectionStatus) -> PaletteTone {
        switch status {
        case .connected: return .positive
        case .failed: return .negative
        case .pairing: return .warning
        case .searching, .idle: return .neutral
        }
    }

    private static func color(hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
