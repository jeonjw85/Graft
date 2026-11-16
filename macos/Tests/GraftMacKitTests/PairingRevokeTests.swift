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
