import XCTest
@testable import GraftApp

final class MenuStateTests: XCTestCase {
    func testIdleShowsPairAction() {
        let state = MenuState(status: .idle, peerName: nil)
        XCTAssertTrue(state.canPair)
        XCTAssertEqual(state.statusLabel, "Not connected")
    }

    func testSearchingExplainsWhatIsHappening() {
        let state = MenuState(status: .searching, peerName: nil)
        XCTAssertEqual(state.statusLabel, "Looking for your phone…")
        XCTAssertTrue(state.canPair)
    }

    func testPairingShowsTheWindowHint() {
        let state = MenuState(status: .pairing, peerName: nil)
        XCTAssertEqual(state.statusLabel, "Waiting for the phone to scan…")
        XCTAssertFalse(state.canPair, "a pairing window is already open")
    }

    func testConnectedShowsPeerName() {
        let state = MenuState(status: .connected(peer: "Galaxy S24"), peerName: "Galaxy S24")
        XCTAssertFalse(state.canPair, "already connected")
        XCTAssertEqual(state.statusLabel, "Connected to Galaxy S24")
    }

    func testFailureExposesTheMessage() {
        let state = MenuState(
            status: .failed(message: "Local network permission denied"),
            peerName: nil
        )
        XCTAssertEqual(state.statusLabel, "Local network permission denied")
        XCTAssertTrue(state.canPair)
    }
}
