import XCTest
@testable import GraftMacKit

final class TransferHistoryTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)
    private lazy var history = TransferHistory(capacity: 20, clock: { self.now })

    func testNewestFirst() {
        history.record(name: "a.bin", bytes: 10, direction: .receive, failure: nil)
        history.record(name: "b.bin", bytes: 20, direction: .send, failure: nil)

        XCTAssertEqual(history.records.map(\.name), ["b.bin", "a.bin"])
    }

    func testCappedAtTwenty() {
        for index in 1...25 {
            history.record(name: "f\(index)", bytes: 1, direction: .receive, failure: nil)
        }

        XCTAssertEqual(history.records.count, 20)
        XCTAssertEqual(history.records.first?.name, "f25")
        XCTAssertFalse(history.records.contains { $0.name == "f5" })
    }

    func testFailureKeepsItsReason() {
        let record = history.record(
            name: "a.bin",
            bytes: 10,
            direction: .receive,
            failure: .hashMismatch
        )

        XCTAssertEqual(record.failure, .hashMismatch)
        XCTAssertEqual(TransferHistory.describe(.hashMismatch), "Hash mismatch")
    }

    func testCompletedHasNoFailure() {
        let record = history.record(name: "a.bin", bytes: 10, direction: .send, failure: nil)

        XCTAssertNil(record.failure)
        XCTAssertEqual(TransferHistory.describe(nil), "Completed")
    }

    func testEveryFailureHasWords() {
        let failures: [TransferFailure] = [
            .unpairedPeer, .duplicateTransfer, .unsafeName, .wrongOffset, .hashMismatch
        ]
        for failure in failures {
            XCTAssertFalse(TransferHistory.describe(failure).isEmpty)
        }
    }

    func testTheSameIdUnderANewNameKeepsBothRows() {
        history.record(id: 1, name: "photo.jpg", bytes: 10, direction: .receive, failure: nil)
        history.record(id: 1, name: "cat.jpg", bytes: 20, direction: .receive, failure: nil)

        XCTAssertEqual(history.records.map(\.name), ["cat.jpg", "photo.jpg"])
    }

    func testARetryOfTheSameFileReplacesItsRow() {
        history.record(id: 1, name: "photo.jpg", bytes: 10, direction: .receive, failure: .wrongOffset)
        history.record(id: 1, name: "photo.jpg", bytes: 10, direction: .receive, failure: nil)

        XCTAssertEqual(history.records.count, 1)
        XCTAssertNil(history.records.first?.failure)
    }

    func testClearEmptiesTheList() {
        history.record(name: "a.bin", bytes: 1, direction: .receive, failure: nil)
        history.clear()

        XCTAssertTrue(history.records.isEmpty)
    }
}
