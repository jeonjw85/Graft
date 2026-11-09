import Foundation

/// Decides whether a peer certificate may connect.
///
/// A peer is authorized when its fingerprint is in the stored peer list, or while a pairing window
/// is open. The window is a closure rather than a captured token store so the server and the
/// pairing service always read the same state; a copied struct would silently disagree.
public final class PeerAuthorizer: @unchecked Sendable {
    private let allowed: () -> Set<String>
    private let isPairingOpen: () -> Bool
    private let clock: () -> Date
    public private(set) var windowOpenedAt: Date?

    public init(
        allowed: @escaping () -> Set<String>,
        isPairingOpen: @escaping () -> Bool = { false },
        clock: @escaping () -> Date = Date.init
    ) {
        self.allowed = allowed
        self.isPairingOpen = isPairingOpen
        self.clock = clock
    }

    public func isAuthorized(_ fingerprint: String) -> Bool {
        if allowed().contains(fingerprint) { return true }
        return isPairingOpen()
    }

    public func openPairingWindow() {
        windowOpenedAt = clock()
    }

    public func closePairingWindow() {
        windowOpenedAt = nil
    }
}
