import XCTest
@testable import GraftApp
import GraftMacKit

final class SettingsTextTests: XCTestCase {
    func testNotificationStatusText() {
        XCTAssertEqual(GraftService.notificationStatusText(allowed: nil), "Not asked yet")
        XCTAssertEqual(GraftService.notificationStatusText(allowed: true), "Allowed")
        XCTAssertEqual(
            GraftService.notificationStatusText(allowed: false),
            "Notifications are turned off for Graft"
        )
    }

    func testFailureReasonsAreReadable() {
        XCTAssertEqual(TransferHistory.describe(nil), "Completed")
        XCTAssertEqual(TransferHistory.describe(.hashMismatch), "Hash mismatch")
    }
}
