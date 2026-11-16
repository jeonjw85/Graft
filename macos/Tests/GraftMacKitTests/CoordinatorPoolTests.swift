import XCTest
@testable import GraftMacKit

final class CoordinatorPoolTests: XCTestCase {
    private func coordinator() -> TransferCoordinator {
        TransferCoordinator(
            directory: FileManager.default.temporaryDirectory,
            isPaired: { true },
            sendControl: { _ in },
            sendBinary: { _ in }
        )
    }

    func testAPoolStartsEmpty() {
        XCTAssertTrue(CoordinatorPool().all.isEmpty)
    }

    func testRemovingASessionRemovesItsCoordinator() {
        let pool = CoordinatorPool()
        let session = NSObject()
        let key = ObjectIdentifier(session)

        pool.insert(coordinator(), for: key)
        XCTAssertEqual(pool.count, 1)

        pool.remove(key)

        XCTAssertEqual(pool.count, 0, "a dead session must not keep a coordinator that looks live")
    }

    func testTwoSessionsAreKeptApart() {
        let pool = CoordinatorPool()
        let firstSession = NSObject()
        let secondSession = NSObject()
        let first = ObjectIdentifier(firstSession)
        let second = ObjectIdentifier(secondSession)

        pool.insert(coordinator(), for: first)
        pool.insert(coordinator(), for: second)
        pool.remove(first)

        XCTAssertEqual(pool.count, 1)
        XCTAssertNotNil(pool.coordinator(for: second))
        XCTAssertNil(pool.coordinator(for: first))
    }
}
