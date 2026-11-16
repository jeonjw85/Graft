import XCTest
@testable import GraftApp

final class PairingWindowStateTests: XCTestCase {
    func testAnOfferInProgressHasNoMessage() {
        XCTAssertNil(PairingWindowState.message(hasQR: true, hasManual: false, status: .pairing))
        XCTAssertNil(PairingWindowState.message(hasQR: false, hasManual: true, status: .pairing))
    }

    func testAFailedStartExplainsItself() {
        XCTAssertEqual(
            PairingWindowState.message(hasQR: false, hasManual: false, status: .failed(message: "Port 47800 is in use")),
            "Port 47800 is in use"
        )
    }

    func testAPreparingWindowAfterPairingSaysSo() {
        XCTAssertEqual(
            PairingWindowState.message(hasQR: false, hasManual: false, status: .connected(peer: "Galaxy")),
            "Paired."
        )
    }
}
