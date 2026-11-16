import Foundation
import GraftProtocol
import UserNotifications

/// The slice of `UNUserNotificationCenter` the presenter needs, so tests do not touch the system.
public protocol NotificationCenterPort {
    func add(title: String, body: String)
}

/// Posts Android notifications as local macOS notifications and keeps a recent list in memory.
public final class NotificationPresenter {
    public struct Recent: Equatable, Sendable {
        public let app: String
        public let title: String
        public let body: String
        public let postedAt: UInt64
    }

    public static let maxBodyBytes = 2 * 1024
    public static let maxRecent = 50

    private let port: NotificationCenterPort
    public private(set) var recent: [Recent] = []

    public init(port: NotificationCenterPort) {
        self.port = port
    }

    /// Returns whether the frame was presented.
    @discardableResult
    public func present(_ envelope: Envelope) -> Bool {
        guard envelope.type == "notification.posted" else { return false }
        guard case .object(let payload) = envelope.payload else { return false }
        guard let app = payload["app"]?.stringValue,
              let text = payload["text"]?.stringValue else {
            return false
        }

        let title = payload["title"]?.stringValue ?? ""
        let postedAt = payload["ts"]?.numberValue.map(UInt64.init) ?? 0
        let body = Self.truncate(text, maxBytes: Self.maxBodyBytes)

        port.add(title: app, body: body)
        recent.insert(Recent(app: app, title: title, body: body, postedAt: postedAt), at: 0)
        if recent.count > Self.maxRecent {
            recent.removeLast(recent.count - Self.maxRecent)
        }
        return true
    }

    /// Truncates on a UTF-8 boundary so a Korean character is never cut in half.
    public static func truncate(_ value: String, maxBytes: Int) -> String {
        guard value.utf8.count > maxBytes else { return value }
        var result = ""
        var used = 0
        for character in value {
            let size = String(character).utf8.count
            if used + size > maxBytes { break }
            result.append(character)
            used += size
        }
        return result
    }
}

/// Adapter around the real notification center.
public struct SystemNotificationCenter: NotificationCenterPort {
    public init() {}

    public func add(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
