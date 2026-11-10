import Foundation
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

final class PairingServiceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)
    private let phone = "phone-fingerprint"
    private let stranger = "stranger-fingerprint"

    private func service(paired: Set<String> = []) throws -> PairingService {
        let identity = try DeviceIdentity.loadOrCreate(tag: "kr.jjw.graft.test.pairing.\(UUID().uuidString)")
        return PairingService(local: identity, paired: paired)
    }

    func testUnpairedPeerCannotSendHello() throws {
        let service = try service()

        let outcome = service.handle(
            envelope: Envelope(type: "hello", payload: .object([:])),
            peerFingerprint: stranger,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "error")
        XCTAssertEqual(outcome.replies.first?.payload.objectValue?["code"]?.stringValue, "unpaired")
        XCTAssertTrue(outcome.close, "an unpaired peer must be disconnected")
    }

    func testUnpairedPeerCannotPing() throws {
        let service = try service()

        let outcome = service.handle(
            envelope: Envelope(type: "ping", payload: .object([:])),
            peerFingerprint: stranger,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "error")
        XCTAssertTrue(outcome.close)
    }

    func testPairedPeerGetsHelloOk() throws {
        let service = try service(paired: [phone])

        let outcome = service.handle(
            envelope: Envelope(type: "hello", payload: .object([:])),
            peerFingerprint: phone,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "hello.ok")
        XCTAssertFalse(outcome.close)
    }

    func testPairedPeerGetsPong() throws {
        let service = try service(paired: [phone])

        let outcome = service.handle(
            envelope: Envelope(type: "ping", payload: .object([:])),
            peerFingerprint: phone,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "pong")
        XCTAssertFalse(outcome.close)
    }

    func testPairingStoresTheAuthenticatedFingerprint() throws {
        let service = try service()
        let issued = service.beginPairing(now: now, host: "127.0.0.1", port: 47800, deviceName: "Mac")

        let outcome = service.handle(
            envelope: Envelope(
                type: "pair.request",
                payload: .object([
                    "token": .string(issued.token),
                    "deviceId": .string("android-1"),
                    "name": .string("Phone"),
                    "platform": .string("android")
                ])
            ),
            peerFingerprint: phone,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "pair.ok")
        XCTAssertTrue(service.allowedFingerprints.contains(phone))
        XCTAssertFalse(service.isPairingOpen(now: now), "a consumed token closes the window")
    }

    func testReplayingPairRequestOnTheSameConnectionIsRejected() throws {
        let service = try service()
        let issued = service.beginPairing(now: now, host: "127.0.0.1", port: 47800, deviceName: "Mac")
        let request = Envelope(
            type: "pair.request",
            payload: .object([
                "token": .string(issued.token),
                "deviceId": .string("android-1"),
                "name": .string("Phone"),
                "platform": .string("android")
            ])
        )

        let first = service.handle(envelope: request, peerFingerprint: phone, now: now)
        XCTAssertEqual(first.replies.first?.type, "pair.ok")

        let second = service.handle(envelope: request, peerFingerprint: phone, now: now)
        XCTAssertEqual(second.replies.first?.type, "error")
        XCTAssertTrue(second.close, "a replayed pair.request must close the connection")
    }

    func testExpiredTokenIsRejected() throws {
        let service = try service()
        let issued = service.beginPairing(now: now, host: "127.0.0.1", port: 47800, deviceName: "Mac")

        let outcome = service.handle(
            envelope: Envelope(
                type: "pair.request",
                payload: .object(["token": .string(issued.token)])
            ),
            peerFingerprint: phone,
            now: now.addingTimeInterval(121)
        )

        XCTAssertEqual(outcome.replies.first?.type, "error")
        XCTAssertFalse(service.allowedFingerprints.contains(phone))
    }

    func testFingerprintFromAnotherPeerIsNeverAccepted() throws {
        let service = try service(paired: [phone])

        let outcome = service.handle(
            envelope: Envelope(type: "clipboard.update", payload: .object([:])),
            peerFingerprint: stranger,
            now: now
        )

        XCTAssertEqual(outcome.replies.first?.type, "error")
        XCTAssertTrue(outcome.close)
    }
}
