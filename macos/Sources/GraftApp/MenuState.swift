import Foundation

enum ConnectionStatus: Equatable {
    case idle
    case searching
    case pairing
    case connected(peer: String)
    case failed(message: String)
}

struct MenuState {
    var status: ConnectionStatus
    var peerName: String?

    var statusLabel: String {
        switch status {
        case .idle:
            return "Not connected"
        case .searching:
            return "Looking for your phone…"
        case .pairing:
            return "Waiting for the phone to scan…"
        case .connected(let peer):
            return "Connected to \(peer)"
        case .failed(let message):
            return message
        }
    }

    /// Pairing is offered whenever no window is open. Being connected does not block re-pairing a
    /// second device later, but v0.1 is one-to-one, so a live connection hides the action.
    var canPair: Bool {
        switch status {
        case .pairing, .connected:
            return false
        case .idle, .searching, .failed:
            return true
        }
    }

    var isConnected: Bool {
        if case .connected = status { return true }
        return false
    }

    var errorMessage: String? {
        if case .failed(let message) = status { return message }
        return nil
    }
}
