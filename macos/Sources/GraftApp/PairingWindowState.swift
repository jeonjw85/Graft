import Foundation

/// What the pairing window says when it has no offer to show.
enum PairingWindowState {
    static func message(hasQR: Bool, hasManual: Bool, status: ConnectionStatus) -> String? {
        if hasQR || hasManual { return nil }
        switch status {
        case .failed(let message): return message
        case .connected: return "Paired."
        case .idle, .searching, .pairing: return "Preparing the pairing offer…"
        }
    }
}
