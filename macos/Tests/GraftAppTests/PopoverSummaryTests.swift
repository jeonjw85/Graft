import XCTest
@testable import GraftApp

final class PopoverSummaryTests: XCTestCase {
    func testConnectedSummaryNamesThePeer() {
        let summary = PopoverSummary.from(
            state: MenuState(status: .connected(peer: "Galaxy S24"), peerName: "Galaxy S24"),
            peerAddress: "192.168.0.12:47800",
            fingerprintPrefix: "Am_YijwV"
        )

        XCTAssertEqual(summary.title, "Connected to Galaxy S24")
        XCTAssertEqual(summary.detail, "192.168.0.12:47800 · Am_YijwV")
    }

    func testIdleSummaryPromptsToOpenThePhoneApp() {
        let summary = PopoverSummary.from(
            state: MenuState(status: .idle, peerName: nil),
            peerAddress: nil,
            fingerprintPrefix: nil
        )

        XCTAssertEqual(summary.title, "Not connected")
        XCTAssertEqual(summary.detail, "Open Graft on your phone")
    }

    func testPairingSummaryPutsTheFingerprintFirst() {
        let summary = PopoverSummary.from(
            state: MenuState(status: .pairing, peerName: nil),
            peerAddress: "10.0.0.5:47800",
            fingerprintPrefix: "Am_YijwV"
        )

        XCTAssertEqual(summary.title, "Waiting for the phone to scan…")
        XCTAssertEqual(summary.detail, "Am_YijwV · 10.0.0.5:47800")
    }

    func testAFailureKeepsItsMessage() {
        let summary = PopoverSummary.from(
            state: MenuState(status: .failed(message: "Port in use"), peerName: nil),
            peerAddress: nil,
            fingerprintPrefix: nil
        )

        XCTAssertEqual(summary.title, "Port in use")
        XCTAssertTrue(summary.showsError)
    }
}
