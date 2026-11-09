import Foundation
import GraftProtocol
import Security
import XCTest
@testable import GraftMacKit

final class DeviceIdentityTests: XCTestCase {
    func testCreateThenLoadReturnsSameFingerprint() throws {
        let tag = "kr.jjw.graft.test.\(UUID().uuidString)"
        defer { DeviceIdentity.delete(tag: tag) }

        let first = try DeviceIdentity.loadOrCreate(tag: tag)
        let second = try DeviceIdentity.loadOrCreate(tag: tag)

        XCTAssertEqual(first.fingerprint, second.fingerprint)
    }

    func testGeneratedCertificateIsSelfSignedP256() throws {
        let tag = "kr.jjw.graft.test.\(UUID().uuidString)"
        defer { DeviceIdentity.delete(tag: tag) }

        let deviceIdentity = try DeviceIdentity.loadOrCreate(tag: tag)
        var certificate: SecCertificate?
        XCTAssertEqual(SecIdentityCopyCertificate(deviceIdentity.identity, &certificate), errSecSuccess)

        let key = try XCTUnwrap(SecCertificateCopyKey(try XCTUnwrap(certificate)))
        let attributes = try XCTUnwrap(SecKeyCopyAttributes(key) as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrKeySizeInBits as String] as? Int, 256)
        XCTAssertEqual(attributes[kSecAttrKeyType as String] as? String, kSecAttrKeyTypeECSECPrimeRandom as String)
    }

    func testCertificateFingerprintMatchesSPKIofItsKey() throws {
        let tag = "kr.jjw.graft.test.\(UUID().uuidString)"
        defer { DeviceIdentity.delete(tag: tag) }

        let deviceIdentity = try DeviceIdentity.loadOrCreate(tag: tag)

        XCTAssertEqual(
            deviceIdentity.fingerprint,
            try SpkiFingerprint.base64URL(certificate: deviceIdentity.certificate)
        )
    }

    func testDistinctTagsProduceDistinctIdentities() throws {
        let firstTag = "kr.jjw.graft.test.\(UUID().uuidString)"
        let secondTag = "kr.jjw.graft.test.\(UUID().uuidString)"
        defer {
            DeviceIdentity.delete(tag: firstTag)
            DeviceIdentity.delete(tag: secondTag)
        }

        let first = try DeviceIdentity.loadOrCreate(tag: firstTag)
        let second = try DeviceIdentity.loadOrCreate(tag: secondTag)

        XCTAssertNotEqual(first.fingerprint, second.fingerprint)
    }

    func testLoadIsStableAcrossCalls() throws {
        let tag = "kr.jjw.graft.test.\(UUID().uuidString)"
        defer { DeviceIdentity.delete(tag: tag) }

        var fingerprints: Set<String> = []
        for _ in 0..<3 {
            fingerprints.insert(try DeviceIdentity.loadOrCreate(tag: tag).fingerprint)
        }
        XCTAssertEqual(fingerprints.count, 1)
    }
}
