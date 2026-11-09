import Foundation
import GraftProtocol
import Network
import Security

/// Decides whether a peer certificate may connect.
///
/// A peer is authorized when its fingerprint is in the stored peer list, or when a pairing window
/// is open. The window is bounded by the token's lifetime so an open window cannot be left behind.
public final class PeerAuthorizer {
    private let allowed: () -> Set<String>
    private let tokens: PairingTokenStore
    private let clock: () -> Date
    private var windowOpenedAt: Date?

    public init(
        allowed: @escaping () -> Set<String>,
        tokens: PairingTokenStore,
        clock: @escaping () -> Date = Date.init
    ) {
        self.allowed = allowed
        self.tokens = tokens
        self.clock = clock
    }

    public func isAuthorized(_ fingerprint: String) -> Bool {
        if allowed().contains(fingerprint) { return true }
        guard let windowOpenedAt else { return false }
        guard let expiresAt = tokens.expiresAt, clock() < expiresAt else { return false }
        return windowOpenedAt <= expiresAt
    }

    public func openPairingWindow() {
        windowOpenedAt = clock()
    }

    public func closePairingWindow() {
        windowOpenedAt = nil
    }
}
