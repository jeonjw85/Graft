import XCTest
@testable import GraftMacKit
import GraftProtocol

final class NumericPairingTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)

    private func service() throws -> PairingService {
        let tag = "kr.jjw.graft.test.numeric.\(UUID().uuidString)"
        let identity = try DeviceIdentity.loadOrCreate(tag: tag)
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        return PairingService(local: identity)
    }

    func testNumericPairingIssuesSixDigits() throws {
        let service = try service()

        let pairing = service.beginNumericPairing(
            now: now,
            host: "10.0.0.5",
            port: 47800,
            deviceName: "Mac"
        )

        XCTAssertEqual(pairing.code.count, 6)
        XCTAssertTrue(pairing.code.allSatisfy(\.isNumber))
        XCTAssertEqual(pairing.host, "10.0.0.5")
        XCTAssertEqual(pairing.port, 47800)
    }

    func testPrefixIsEightCharactersOfTheIdentity() throws {
        let service = try service()
        let pairing = service.beginNumericPairing(
            now: now,
            host: "10.0.0.5",
            port: 47800,
            deviceName: "Mac"
        )

        XCTAssertEqual(pairing.fingerprintPrefix.count, 8)
        XCTAssertTrue(pairing.fullFingerprint.hasPrefix(pairing.fingerprintPrefix))
        XCTAssertEqual(pairing.fullFingerprint.count, 43)
    }

    func testTheNumericCodeIsAcceptedOnceByPairRequest() throws {
        let service = try service()
        let pairing = service.beginNumericPairing(
            now: now,
            host: "10.0.0.5",
            port: 47800,
            deviceName: "Mac"
        )
        let request = Envelope(
            type: "pair.request",
            payload: .object([
                "token": .string(pairing.code),
                "deviceId": .string("android-1"),
                "name": .string("Galaxy S24"),
                "platform": .string("android")
            ])
        )

        let first = service.handle(envelope: request, peerFingerprint: "peer", now: now)
        XCTAssertEqual(first.replies.first?.type, "pair.ok")

        let second = service.handle(envelope: request, peerFingerprint: "other-peer", now: now)
        XCTAssertEqual(second.replies.first?.type, "error", "the code is single use")
    }

    func testPairingWindowClosesAfterTheCodeIsUsed() throws {
        let service = try service()
        let pairing = service.beginNumericPairing(
            now: now,
            host: "10.0.0.5",
            port: 47800,
            deviceName: "Mac"
        )
        XCTAssertTrue(service.isPairingOpen(now: now))

        _ = service.handle(
            envelope: Envelope(
                type: "pair.request",
                payload: .object(["token": .string(pairing.code)])
            ),
            peerFingerprint: "peer",
            now: now
        )

        XCTAssertFalse(service.isPairingOpen(now: now))
    }
}
