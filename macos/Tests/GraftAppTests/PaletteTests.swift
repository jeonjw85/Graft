import XCTest
@testable import GraftApp
import GraftProtocol

final class PaletteTests: XCTestCase {
    func testStatusTones() {
        XCTAssertEqual(Palette.tone(for: .connected(peer: "phone")), .positive)
        XCTAssertEqual(Palette.tone(for: .idle), .neutral)
        XCTAssertEqual(Palette.tone(for: .searching), .neutral)
        XCTAssertEqual(Palette.tone(for: .pairing), .warning)
        XCTAssertEqual(Palette.tone(for: .failed(message: "no")), .negative)
    }

    func testTheTokensMatchTheSpec() {
        XCTAssertEqual(Palette.backgroundHex, 0x0E1116)
        XCTAssertEqual(Palette.surfaceHex, 0x151A21)
        XCTAssertEqual(Palette.surfaceVariantHex, 0x1C232C)
        XCTAssertEqual(Palette.primaryHex, 0x5B8CFF)
        XCTAssertEqual(Palette.onSurfaceHex, 0xE6EAF0)
        XCTAssertEqual(Palette.onSurfaceVariantHex, 0x9BA6B4)
        XCTAssertEqual(Palette.outlineHex, 0x2A3240)
        XCTAssertEqual(Palette.connectedHex, 0x35C46B)
        XCTAssertEqual(Palette.warningHex, 0xF0A63C)
        XCTAssertEqual(Palette.errorHex, 0xF2555A)
    }
}
