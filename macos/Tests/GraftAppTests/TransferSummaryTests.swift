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

    func testSpeedTrackerComputesThroughputAndETA() throws {
        let tracker = TransferSpeedTracker()
        let t0 = Date(timeIntervalSince1970: 1_000)

        let initial = [UInt32(1): TransferProgress(name: "video.mp4", confirmed: 0, total: 20 * 1024 * 1024, direction: .send)]
        XCTAssertTrue(tracker.update(transfers: initial, now: t0).isEmpty)

        // Two seconds later: 4 MiB moved -> 2.0 MiB/s, 16 MiB remaining -> 8s left.
        let later = [UInt32(1): TransferProgress(name: "video.mp4", confirmed: 4 * 1024 * 1024, total: 20 * 1024 * 1024, direction: .send)]
        let metrics = try XCTUnwrap(tracker.update(transfers: later, now: t0.addingTimeInterval(2))[1])

        XCTAssertEqual(metrics.label, "2.0 MiB/s · 8s left")
        XCTAssertEqual(TransferMetrics.formatETA(0.4), "< 1s left")
        XCTAssertEqual(TransferMetrics.formatETA(125), "2m 5s left")
    }
}
