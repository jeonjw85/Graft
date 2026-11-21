import AppKit
import XCTest
@testable import GraftApp

@MainActor
final class WindowCloseRevokerTests: XCTestCase {
    func testClosingTheWindowRunsTheAction() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 200, height: 200),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        var revoked = false
        let revoker = WindowCloseRevoker(window: window) { revoked = true }

        NotificationCenter.default.post(name: NSWindow.willCloseNotification, object: window)

        XCTAssertTrue(revoked, "closing must revoke the pairing offer")
        withExtendedLifetime(revoker) {}
    }

    func testAWindowWithoutTheObserverIsNotAffected() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 100, height: 100),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        var revoked = false
        let revoker = WindowCloseRevoker(window: window) { revoked = true }

        NotificationCenter.default.post(
            name: NSWindow.willCloseNotification,
            object: NSWindow()
        )

        XCTAssertFalse(revoked)
        withExtendedLifetime(revoker) {}
    }
}
