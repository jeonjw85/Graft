import Foundation
import XCTest
@testable import GraftProtocol

final class PairingTokenStoreTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)

    func testTokenConsumesOnce() {
        var store = PairingTokenStore()
        let token = store.issue(now: now)

        XCTAssertTrue(store.attempt(token: token.value, now: now))
        XCTAssertFalse(store.attempt(token: token.value, now: now), "replay must fail")
    }

    func testExpiredTokenNeverConsumes() {
        var store = PairingTokenStore(ttl: 120)
        let token = store.issue(now: now)

        XCTAssertFalse(store.attempt(token: token.value, now: now.addingTimeInterval(121)))
        XCTAssertTrue(
            store.attempt(token: token.value, now: now.addingTimeInterval(119)),
            "a rejected expired attempt must not burn the token"
        )
    }

    func testUnknownTokenIsRejected() {
        var store = PairingTokenStore()
        _ = store.issue(now: now)

        XCTAssertFalse(store.attempt(token: "unrelated-token-value", now: now))
    }

    func testSixthAttemptInsideWindowIsRejected() {
        var store = PairingTokenStore(maxAttempts: 5)
        _ = store.issue(now: now)

        for index in 0..<5 {
            XCTAssertFalse(store.attempt(token: "wrong-\(index)-value-here", now: now))
        }
        XCTAssertFalse(store.attempt(token: "wrong-5-value-here", now: now))
        XCTAssertTrue(store.isRateLimited(now: now))
    }

    func testRateLimitWindowSlides() {
        var store = PairingTokenStore(maxAttempts: 5)
        _ = store.issue(now: now)

        for index in 0..<5 {
            _ = store.attempt(token: "wrong-\(index)-value-here", now: now)
        }
        XCTAssertTrue(store.isRateLimited(now: now))
        XCTAssertFalse(store.isRateLimited(now: now.addingTimeInterval(61)))
    }

    func testNumericCodeIsSixDigits() {
        var store = PairingTokenStore()
        for _ in 0..<1000 {
            let token = store.issueNumericCode(now: now)
            XCTAssertEqual(token.value.count, 6)
            XCTAssertTrue(token.value.allSatisfy { $0.isNumber })
        }
    }

    func testNumericCodeConsumesOnce() {
        var store = PairingTokenStore()
        let code = store.issueNumericCode(now: now)

        XCTAssertTrue(store.attempt(token: code.value, now: now))
        XCTAssertFalse(store.attempt(token: code.value, now: now))
    }

    func testIssuingReplacesPreviousToken() {
        var store = PairingTokenStore()
        let first = store.issue(now: now)
        let second = store.issue(now: now)

        XCTAssertFalse(store.attempt(token: first.value, now: now), "only the newest token is valid")
        XCTAssertTrue(store.attempt(token: second.value, now: now))
    }

    func testTokenIsInactiveAfterConsumption() {
        var store = PairingTokenStore()
        let token = store.issue(now: now)
        XCTAssertTrue(store.isActive(now: now))

        XCTAssertTrue(store.attempt(token: token.value, now: now))
        XCTAssertFalse(store.isActive(now: now), "a consumed token must close the pairing window")
    }

    func testTokenIsInactiveAfterExpiry() {
        var store = PairingTokenStore(ttl: 120)
        _ = store.issue(now: now)

        XCTAssertFalse(store.isActive(now: now.addingTimeInterval(121)))
    }
}
