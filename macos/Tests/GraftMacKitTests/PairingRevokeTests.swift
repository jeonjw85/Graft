import XCTest
@testable import GraftMacKit
import GraftProtocol

final class PairingRevokeTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)

    private func service() throws -> PairingService {
        let tag = "kr.jjw.graft.test.revoke.\(UUID().uuidString)"
        let identity = try DeviceIdentity.loadOrCreate(tag: tag)
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        return PairingService(local: identity)
    }

    func testRevokingClosesPairing() throws {
        let service = try service()
        _ = service.beginPairing(now: now, host: "10.0.0.5", port: 47800, deviceName: "Mac")
        XCTAssertTrue(service.isPairingOpen(now: now))

        service.revokePairing()

        XCTAssertFalse(service.isPairingOpen(now: now))
    }

    func testARevokedTokenCannotPair() throws {
        let service = try service()
        let pairing = service.beginPairing(now: now, host: "10.0.0.5", port: 47800, deviceName: "Mac")
        service.revokePairing()

        let result = service.handle(
            envelope: Envelope(
                type: "pair.request",
                payload: .object([
                    "token": .string(pairing.token),
                    "deviceId": .string("android-1"),
                    "name": .string("Galaxy"),
                    "platform": .string("android")
                ])
            ),
            peerFingerprint: "peer",
            now: now
        )

        XCTAssertEqual(result.replies.first?.type, "error", "a closed window must not pair")
    }
}

final class PairingForgetTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)

    private func service() throws -> PairingService {
        let tag = "kr.jjw.graft.test.forget.\(UUID().uuidString)"
        let identity = try DeviceIdentity.loadOrCreate(tag: tag)
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        return PairingService(local: identity)
    }

    func testForgettingAPeerRemovesTheTlsAllowance() throws {
        let service = try service()
        let pairing = service.beginPairing(now: now, host: "10.0.0.5", port: 47800, deviceName: "Mac")
        _ = service.handle(
            envelope: Envelope(
                type: "pair.request",
                payload: .object([
                    "token": .string(pairing.token),
                    "deviceId": .string("android-1"),
                    "name": .string("Galaxy"),
                    "platform": .string("android")
                ])
            ),
            peerFingerprint: "peer-fp",
            now: now
        )
        XCTAssertTrue(service.allowedFingerprints.contains("peer-fp"))

        service.forget(fingerprint: "peer-fp")

        XCTAssertFalse(
            service.allowedFingerprints.contains("peer-fp"),
            "a forgotten phone must not stay authorized at the TLS layer"
        )
        XCTAssertNil(
            service.lastPairedPeer,
            "a forgotten peer must not be persisted again on the next message"
        )
    }

    func testForgettingOnePeerKeepsAnother() throws {
        let service = try service()
        for (index, name) in ["A", "B"].enumerated() {
            let pairing = service.beginPairing(
                now: now,
                host: "10.0.0.5",
                port: 47800,
                deviceName: "Mac"
            )
            _ = service.handle(
                envelope: Envelope(
                    type: "pair.request",
                    payload: .object([
                        "token": .string(pairing.token),
                        "deviceId": .string("device-\(index)"),
                        "name": .string(name),
                        "platform": .string("android")
                    ])
                ),
                peerFingerprint: "fp-\(name)",
                now: now
            )
        }

        service.forget(fingerprint: "fp-A")

        XCTAssertEqual(service.allowedFingerprints, ["fp-B"])
    }
}
