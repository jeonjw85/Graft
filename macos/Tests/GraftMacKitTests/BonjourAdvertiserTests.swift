import XCTest
@testable import GraftMacKit

final class BonjourAdvertiserTests: XCTestCase {
    private let fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"

    func testServiceNameCarriesShortFingerprint() {
        XCTAssertEqual(
            BonjourServiceInfo.serviceName(deviceName: "MacBook Pro", fingerprint: fingerprint),
            "MacBook Pro (Am_YijwV)"
        )
    }

    func testShortFingerprintRoundTrips() {
        let name = BonjourServiceInfo.serviceName(deviceName: "Mac", fingerprint: fingerprint)
        XCTAssertEqual(BonjourServiceInfo.shortFingerprint(from: name), "Am_YijwV")
    }

    func testNameWithoutSuffixHasNoFingerprint() {
        XCTAssertNil(BonjourServiceInfo.shortFingerprint(from: "MacBook Pro"))
    }

    func testKoreanNameTruncatesOnBoundary() {
        let long = String(repeating: "가", count: 40)
        let name = BonjourServiceInfo.serviceName(deviceName: long, fingerprint: fingerprint)

        XCTAssertLessThanOrEqual(name.utf8.count, BonjourServiceInfo.maxNameBytes)
        XCTAssertFalse(name.contains("\u{FFFD}"))
    }

    func testDistinctFingerprintsProduceDistinctNames() {
        XCTAssertNotEqual(
            BonjourServiceInfo.serviceName(deviceName: "Mac", fingerprint: "AAAAAAAA1111"),
            BonjourServiceInfo.serviceName(deviceName: "Mac", fingerprint: "BBBBBBBB2222")
        )
    }

    func testTxtRecordCarriesTheFullFingerprint() throws {
        let record = BonjourAdvertiser.txtRecord(fingerprint: fingerprint)
        let text = try XCTUnwrap(String(data: record, encoding: .utf8))

        XCTAssertTrue(text.contains("fp=\(fingerprint)"))
        XCTAssertTrue(text.contains("v=1"))
        XCTAssertLessThanOrEqual(record.count, 255)
    }

    func testAdvertiserRejectsPortZero() {
        let advertiser = BonjourAdvertiser(port: 0, fingerprint: fingerprint, deviceName: "Mac")
        XCTAssertThrowsError(try advertiser.start())
    }

    func testStopTwiceIsSafe() {
        let advertiser = BonjourAdvertiser(port: 47999, fingerprint: fingerprint, deviceName: "Mac")
        advertiser.stop()
        advertiser.stop()
    }
}
