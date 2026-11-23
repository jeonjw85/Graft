import Foundation
import GraftProtocol
import UserNotifications

/// The slice of `UNUserNotificationCenter` the presenter needs, so tests do not touch the system.
public protocol NotificationCenterPort {
    func add(title: String, subtitle: String, body: String)
    func add(identifier: String, title: String, subtitle: String, body: String, replyable: Bool)
    func remove(identifier: String)
}

public extension NotificationCenterPort {
    func add(title: String, body: String) {
        add(title: title, subtitle: "", body: body)
    }

    func add(identifier: String, title: String, subtitle: String, body: String, replyable: Bool) {
        add(title: title, subtitle: subtitle, body: body)
    }

    func remove(identifier: String) {}
}

/// The slice of `UNUserNotificationCenter` that asks for permission, kept separate for tests.
public protocol NotificationAuthorizationPort {
    func request(completion: @escaping @Sendable (Bool) -> Void)
}

/// Posts Android notifications as local macOS notifications and keeps a recent list in memory.
public final class NotificationPresenter: @unchecked Sendable {
    public struct Recent: Equatable, Sendable {
        public let app: String
        public let title: String
        public let body: String
        public let postedAt: UInt64
    }

    public static let maxBodyBytes = 2 * 1024
    public static let maxRecent = 50

    private let port: NotificationCenterPort
    private let authorization: NotificationAuthorizationPort?
    private var askedForAuthorization = false

    public private(set) var recent: [Recent] = []

    /// The user's answer to the system prompt, or nil while it is still open or never asked.
    public private(set) var notificationsAllowed: Bool?

    /// Called with the answer so the UI can show that banners are off.
    public var onAuthorizationChange: ((Bool) -> Void)?

    /// When false, the banner says only which app alerted, never the text.
    public var showPreviews = true

    public init(
        port: NotificationCenterPort,
        authorization: NotificationAuthorizationPort? = nil
    ) {
        self.port = port
        self.authorization = authorization
    }

    /// Asks the system once per launch. A denial is recorded and nothing else changes: pairing,
    /// transfers, and the recent list keep working, only the banners stay silent.
    public func requestAuthorizationIfNeeded() {
        guard let authorization, !askedForAuthorization else { return }
        askedForAuthorization = true
        authorization.request { [weak self] granted in
            self?.notificationsAllowed = granted
            self?.onAuthorizationChange?(granted)
        }
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
        let postedAt = payload["ts"]?.uint64Value ?? 0
        let key = payload["key"]?.stringValue.flatMap { $0.isEmpty ? nil : $0 } ?? UUID().uuidString
        let replyable: Bool
        if case .bool(let flag) = payload["replyable"] {
            replyable = flag
        } else {
            replyable = false
        }
        let body = Self.truncate(text, maxBytes: Self.maxBodyBytes)

        port.add(
            identifier: key,
            title: app,
            subtitle: showPreviews ? title : "",
            body: showPreviews ? body : "New notification",
            replyable: replyable && showPreviews
        )
        recent.insert(Recent(app: app, title: title, body: body, postedAt: postedAt), at: 0)
        if recent.count > Self.maxRecent {
            recent.removeLast(recent.count - Self.maxRecent)
        }
        return true
    }

    /// Removes a previously delivered banner when the phone reports it was dismissed.
    @discardableResult
    public func handleRemoval(_ envelope: Envelope) -> Bool {
        guard envelope.type == "notification.removed" else { return false }
        guard case .object(let payload) = envelope.payload,
              let key = payload["key"]?.stringValue,
              !key.isEmpty else {
            return false
        }
        port.remove(identifier: key)
        return true
    }

    /// Forgets the recent list without changing the user's permission.
    public func clearRecent() {
        recent.removeAll()
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

/// Adapter that asks `UNUserNotificationCenter` for permission.
public struct SystemNotificationAuthorization: NotificationAuthorizationPort {
    public init() {}

    public func request(completion: @escaping @Sendable (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            completion(granted)
        }
    }
}

/// Adapter around the real notification center.
public struct SystemNotificationCenter: NotificationCenterPort {
    public static let replyCategoryId = "GRAFT_REPLYABLE"
    public static let defaultCategoryId = "GRAFT_ALERT"
    public static let replyActionId = "GRAFT_REPLY"

    public init() {}

    /// Registers interactive categories for inline replies and custom dismiss syncing.
    public static func registerCategories() {
        let replyAction = UNTextInputNotificationAction(
            identifier: replyActionId,
            title: "Reply",
            options: [],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Reply…"
        )
        let replyableCategory = UNNotificationCategory(
            identifier: replyCategoryId,
            actions: [replyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let defaultCategory = UNNotificationCategory(
            identifier: defaultCategoryId,
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        UNUserNotificationCenter.current().setNotificationCategories([
            replyableCategory,
            defaultCategory,
        ])
    }

    public func add(title: String, subtitle: String, body: String) {
        add(identifier: UUID().uuidString, title: title, subtitle: subtitle, body: body, replyable: false)
    }

    public func add(identifier: String, title: String, subtitle: String, body: String, replyable: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        if !subtitle.isEmpty {
            content.subtitle = subtitle
        }
        content.body = body
        content.categoryIdentifier = replyable ? Self.replyCategoryId : Self.defaultCategoryId
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    public func remove(identifier: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
    }
}
