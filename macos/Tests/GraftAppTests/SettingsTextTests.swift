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

    func testEnglishAndKoreanLocalizationsHaveMatchingKeys() throws {
        let appBundle = Bundle(for: GraftService.self)
        let enPath = try XCTUnwrap(appBundle.path(forResource: "en", ofType: "lproj"))
        let koPath = try XCTUnwrap(appBundle.path(forResource: "ko", ofType: "lproj"))

        let enTable = try XCTUnwrap(
            NSDictionary(contentsOfFile: "\(enPath)/Localizable.strings") as? [String: String]
        )
        let koTable = try XCTUnwrap(
            NSDictionary(contentsOfFile: "\(koPath)/Localizable.strings") as? [String: String]
        )

        XCTAssertFalse(enTable.isEmpty)
        XCTAssertEqual(Set(enTable.keys), Set(koTable.keys), "en and ko string keys must stay in sync")
        XCTAssertEqual(koTable["Pair a phone"], "휴대폰 연결")
        XCTAssertEqual(koTable["Connected to %@"], "%@에 연결됨")
        for (key, value) in koTable {
            XCTAssertFalse(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "empty ko value for \(key)")
        }
    }
}
