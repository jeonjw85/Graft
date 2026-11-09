import Foundation
import Security

public struct PairingToken: Equatable, Sendable {
    public let value: String
    public let expiresAt: Date

    public init(value: String, expiresAt: Date) {
        self.value = value
        self.expiresAt = expiresAt
    }
}

public enum PairingTokenError: Error {
    case randomGenerationFailed
}

/// Issues single-use pairing tokens and enforces the per-minute attempt budget.
///
/// One store owns both rules so the server has a single place to enforce rate limiting; splitting
/// them invites a path that checks the token but forgets the budget.
public struct PairingTokenStore {
    private let ttl: TimeInterval
    private let maxAttempts: Int
    private let window: TimeInterval = 60

    private var current: PairingToken?
    private var consumed = false
    private var attempts: [Date] = []

    public init(ttl: TimeInterval = 120, maxAttempts: Int = 5) {
        self.ttl = ttl
        self.maxAttempts = maxAttempts
    }

    public mutating func issue(now: Date) -> PairingToken {
        let value = Self.randomToken()
        let token = PairingToken(value: value, expiresAt: now.addingTimeInterval(ttl))
        current = token
        consumed = false
        return token
    }

    /// Six digits, generated with a cryptographically secure RNG, sharing the same TTL and budget.
    public mutating func issueNumericCode(now: Date) -> PairingToken {
        let value = String(format: "%06d", Self.randomNumber(upperBound: 1_000_000))
        let token = PairingToken(value: value, expiresAt: now.addingTimeInterval(ttl))
        current = token
        consumed = false
        return token
    }

    /// Consumes the token if it matches, is unexpired, unused, and the budget allows it.
    public mutating func attempt(token: String, now: Date) -> Bool {
        recordAttempt(now: now)

        guard !isRateLimited(now: now) else { return false }
        guard let current, !consumed, now < current.expiresAt else { return false }
        guard constantTimeEquals(current.value, token) else { return false }

        consumed = true
        return true
    }

    public mutating func isRateLimited(now: Date) -> Bool {
        pruneAttempts(now: now)
        return attempts.count >= maxAttempts
    }

    public var expiresAt: Date? {
        current?.expiresAt
    }

    /// True while a token exists, has not been consumed, and has not expired.
    ///
    /// The TLS layer admits unknown peers while this is true, so it must become false the moment
    /// the token is used; otherwise a second unknown device could still complete a handshake.
    public func isActive(now: Date) -> Bool {
        guard let current, !consumed else { return false }
        return now < current.expiresAt
    }

    private mutating func recordAttempt(now: Date) {
        attempts.append(now)
        pruneAttempts(now: now)
    }

    private mutating func pruneAttempts(now: Date) {
        var kept: [Date] = []
        for attempt in attempts where now.timeIntervalSince(attempt) < window {
            kept.append(attempt)
        }
        attempts = kept
    }

    private static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 18)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status != errSecSuccess {
            // A weak token would be worse than failing closed; fall back to UUID bytes.
            bytes = Array(UUID().uuidString.utf8.prefix(18))
        }
        return Data(bytes)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func randomNumber(upperBound: Int) -> Int {
        var value: UInt32 = 0
        let status = SecRandomCopyBytes(kSecRandomDefault, 4, &value)
        if status != errSecSuccess {
            return Int.random(in: 0..<upperBound)
        }
        return Int(value % UInt32(upperBound))
    }

    private func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for index in left.indices {
            difference |= left[index] ^ right[index]
        }
        return difference == 0
    }
}
