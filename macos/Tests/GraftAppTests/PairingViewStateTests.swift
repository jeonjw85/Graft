import XCTest
@testable import GraftApp

final class PairingViewStateTests: XCTestCase {
    private let validURI = "graft://pair?v=1&h=127.0.0.1&p=47800&fp=Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ&t=042817&n=Mac"

    func testQRImageIsProducedForAValidURI() throws {
        let image = try XCTUnwrap(QRImage.make(from: validURI, scale: 8))

        XCTAssertEqual(image.size.width, image.size.height)
        XCTAssertGreaterThan(image.size.width, 100)
    }

    func testInvalidURIProducesNoImage() {
        XCTAssertNil(QRImage.make(from: "not a uri", scale: 8))
    }

    func testEmptyStringProducesNoImage() {
        XCTAssertNil(QRImage.make(from: "", scale: 8))
    }

    func testFallbackCodeIsSpacedForReading() {
        XCTAssertEqual(PairingViewState(fallbackCode: "042817").displayCode, "042 817")
    }

    func testNonSixDigitCodeIsShownUnchanged() {
        XCTAssertEqual(PairingViewState(fallbackCode: "12345").displayCode, "12345")
        XCTAssertEqual(PairingViewState(fallbackCode: "").displayCode, "")
    }

    func testLongTokenIsNotTreatedAsAFallbackCode() {
        XCTAssertEqual(
            PairingViewState(fallbackCode: "abcdefghijklmnopqrstuvwx").displayCode,
            "abcdefghijklmnopqrstuvwx"
        )
    }
}
