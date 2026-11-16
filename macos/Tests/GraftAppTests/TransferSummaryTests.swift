import XCTest
@testable import GraftApp
import GraftMacKit

final class TransferSummaryTests: XCTestCase {
    private func progress(_ name: String) -> TransferProgress {
        TransferProgress(name: name, confirmed: 10, total: 10, direction: .receive)
    }

    func testFinishedTransfersLeaveTheActiveList() {
        let summary = TransferSummary.from(
            transfers: [1: progress("a.bin"), 2: progress("b.bin")],
            finished: [1],
            failure: nil
        )

        XCTAssertEqual(summary.rows.map(\.progress.name), ["b.bin"])
    }

    func testRowsKeepTheirTransferIds() {
        let summary = TransferSummary.from(
            transfers: [7: progress("a.bin"), 3: progress("b.bin")],
            finished: [],
            failure: nil
        )

        XCTAssertEqual(summary.rows.map(\.id), [3, 7])
    }

    func testTwoFilesWithTheSameNameStayDistinct() {
        let summary = TransferSummary.from(
            transfers: [1: progress("photo.jpg"), 2: progress("photo.jpg")],
            finished: [],
            failure: nil
        )

        XCTAssertEqual(summary.rows.map(\.id), [1, 2])
    }

    func testTheFailureIsPassedThrough() {
        let summary = TransferSummary.from(
            transfers: [:],
            finished: [],
            failure: "The file did not arrive intact"
        )

        XCTAssertEqual(summary.failure, "The file did not arrive intact")
    }
}
