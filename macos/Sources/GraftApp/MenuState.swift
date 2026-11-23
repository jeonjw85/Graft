import Foundation
import GraftProtocol

/// Battery and charging snapshot reported by the connected Android phone.
struct PeerDeviceStatus: Equatable, Sendable {
    let battery: Int
    let charging: Bool

    var batterySymbol: String {
        if charging { return "battery.100.bolt" }
        switch battery {
        case 88...100: return "battery.100"
        case 63..<88: return "battery.75"
        case 38..<63: return "battery.50"
        case 13..<38: return "battery.25"
        default: return "battery.0"
        }
    }

    static func parse(_ envelope: Envelope) -> PeerDeviceStatus? {
        guard envelope.type == "device.status" else { return nil }
        guard case .object(let payload) = envelope.payload else { return nil }
        guard case .number(let rawBattery) = payload["battery"],
              case .bool(let charging) = payload["charging"] else {
            return nil
        }
        let battery = max(0, min(100, Int(rawBattery.rounded())))
        return PeerDeviceStatus(battery: battery, charging: charging)
    }
}

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
