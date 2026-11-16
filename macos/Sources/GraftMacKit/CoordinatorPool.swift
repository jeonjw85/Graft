import Foundation

/// Keeps one transfer coordinator per live connection.
///
/// A coordinator left behind by a closed session looks alive but its send closures captured the old
/// connection, so sending through it silently does nothing. Removing the coordinator with its
/// session is the fix; the pool exists so that rule is testable.
public final class CoordinatorPool {
    private var bySession: [ObjectIdentifier: TransferCoordinator] = [:]

    public init() {}

    public var count: Int { bySession.count }

    public var all: [TransferCoordinator] { Array(bySession.values) }

    public func insert(_ coordinator: TransferCoordinator, for session: ObjectIdentifier) {
        bySession[session] = coordinator
    }

    public func coordinator(for session: ObjectIdentifier) -> TransferCoordinator? {
        bySession[session]
    }

    public func remove(_ session: ObjectIdentifier) {
        bySession.removeValue(forKey: session)
    }

    public func removeAll() {
        bySession.removeAll()
    }
}
