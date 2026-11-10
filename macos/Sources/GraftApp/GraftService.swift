import Foundation
import SwiftUI

/// Placeholder owned by Task 2. Task 1 only needs the app shell to build and run.
@MainActor
final class GraftService: ObservableObject {
    @Published private(set) var menuState = MenuState(status: .idle, peerName: nil)
    @Published private(set) var peers: [PairedPeer] = []

    var statusSymbol: String {
        switch menuState.status {
        case .connected: return "iphone.radiowaves.left.and.right"
        case .pairing: return "qrcode"
        case .searching: return "magnifyingglass"
        case .failed: return "exclamationmark.triangle"
        case .idle: return "iphone.slash"
        }
    }

    var statusColor: Color {
        switch menuState.status {
        case .connected: return .green
        case .failed: return .red
        case .pairing, .searching: return .orange
        case .idle: return .secondary
        }
    }

    var qrImage: NSImage? { nil }
    var fallbackCode: String? { nil }

    func beginPairing() {
        menuState = MenuState(status: .pairing, peerName: nil)
    }

    func forgetPeer(fingerprint: String) {
        peers.removeAll { $0.fingerprint == fingerprint }
    }
}
