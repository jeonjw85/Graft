import Foundation
import XCTest
@testable import GraftProtocol

final class PairingURITests: XCTestCase {
    private let fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"
    private let token = "abcdefghijklmnopqrstuvwx"

    func testBuildMatchesSpecExample() {
        let uri = PairingURI.build(
            host: "192.168.0.12",
            port: 47800,
            fingerprint: fingerprint,
            token: token,
            name: "MacBook Pro"
        )
        XCTAssertEqual(
            uri,
            "graft://pair?v=1&h=192.168.0.12&p=47800&fp=Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ&t=abcdefghijklmnopqrstuvwx&n=MacBook%20Pro"
        )
    }

    func testRoundTrip() throws {
        let uri = PairingURI.build(
            host: "10.0.0.5",
            port: 47800,
            fingerprint: fingerprint,
            token: token,
            name: "작업실 맥"
        )
        let offer = try PairingURI.parse(uri)

        XCTAssertEqual(offer.version, 1)
        XCTAssertEqual(offer.host, "10.0.0.5")
        XCTAssertEqual(offer.port, 47800)
        XCTAssertEqual(offer.fingerprint, fingerprint)
        XCTAssertEqual(offer.token, token)
        XCTAssertEqual(offer.name, "작업실 맥")
    }

    func testRejectsUnsupportedVersion() {
        XCTAssertThrowsError(try PairingURI.parse(validURI().replacingOccurrences(of: "v=1", with: "v=2"))) {
            XCTAssertEqual($0 as? PairingURIError, .unsupportedVersion)
        }
    }

    func testRejectsZeroPort() {
        XCTAssertThrowsError(try PairingURI.parse(validURI().replacingOccurrences(of: "p=47800", with: "p=0"))) {
            XCTAssertEqual($0 as? PairingURIError, .invalidPort)
        }
    }

    func testRejectsBadFingerprintLength() {
        let short = validURI().replacingOccurrences(of: fingerprint, with: "tooshort")
        XCTAssertThrowsError(try PairingURI.parse(short)) {
            XCTAssertEqual($0 as? PairingURIError, .invalidFingerprint)
        }
    }

    func testRejectsShortToken() {
        let short = validURI().replacingOccurrences(of: "t=\(token)", with: "t=abc")
        XCTAssertThrowsError(try PairingURI.parse(short)) {
            XCTAssertEqual($0 as? PairingURIError, .invalidToken)
        }
    }

    func testRejectsNonPairScheme() {
        XCTAssertThrowsError(try PairingURI.parse("https://example.com/?v=1")) {
            XCTAssertEqual($0 as? PairingURIError, .notAPairingURI)
        }
    }

    func testAcceptsNumericFallbackCode() throws {
        let uri = validURI().replacingOccurrences(of: "t=\(token)", with: "t=123456")
        XCTAssertEqual(try PairingURI.parse(uri).token, "123456")
    }

    func testRejectsWrongNumericLength() {
        for bad in ["t=12345", "t=1234567"] {
            let uri = validURI().replacingOccurrences(of: "t=\(token)", with: bad)
            XCTAssertThrowsError(try PairingURI.parse(uri)) {
                XCTAssertEqual($0 as? PairingURIError, .invalidToken)
            }
        }
    }

    private func validURI() -> String {
        PairingURI.build(
            host: "192.168.0.12",
            port: 47800,
            fingerprint: fingerprint,
            token: token,
            name: "MacBook Pro"
        )
    }
}
