import XCTest
@testable import GraftMacKit

final class DeviceIdentityRecoveryTests: XCTestCase {
    func testAnUnreadableTagIsReplacedOnce() throws {
        let tag = "kr.jjw.graft.test.recover.\(UUID().uuidString)"
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        var attempts = 0

        let identity = try DeviceIdentity.loadOrCreate(tag: tag) { attemptTag in
            attempts += 1
            if attempts == 1 {
                // What a Keychain ACL denial or a half-deleted item looks like to the caller.
                throw IdentityError.identityLookup
            }
            return try DeviceIdentity.loadOrCreateOnce(tag: attemptTag)
        }

        XCTAssertEqual(attempts, 2, "one retry, no loop")
        XCTAssertFalse(identity.fingerprint.isEmpty)
    }

    func testAFailingRetrySurfacesTheError() {
        let tag = "kr.jjw.graft.test.recover.\(UUID().uuidString)"
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        var attempts = 0

        XCTAssertThrowsError(
            try DeviceIdentity.loadOrCreate(tag: tag) { _ in
                attempts += 1
                throw IdentityError.identityLookup
            }
        )
        XCTAssertEqual(attempts, 2)
    }

    func testAWorkingTagIsOpenedOnce() throws {
        let tag = "kr.jjw.graft.test.recover.\(UUID().uuidString)"
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        let first = try DeviceIdentity.loadOrCreate(tag: tag)
        var attempts = 0

        let second = try DeviceIdentity.loadOrCreate(tag: tag) { attemptTag in
            attempts += 1
            return try DeviceIdentity.loadOrCreateOnce(tag: attemptTag)
        }

        XCTAssertEqual(attempts, 1)
        XCTAssertEqual(first.fingerprint, second.fingerprint)
    }
}
