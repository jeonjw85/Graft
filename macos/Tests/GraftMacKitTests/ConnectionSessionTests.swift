import Foundation
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

/// Drives one connection through the shared state machine, heartbeat, and pairing rules.
///
/// This is the piece that makes SessionMachine and Heartbeat load-bearing instead of library code
/// that only tests exercise.
final class ConnectionSessionTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_762_752_000)
    private let phone = "phone-fingerprint"

    private func makeSession(
        paired: Set<String> = [],
        send: @escaping (Envelope) -> Void = { _ in },
        close: @escaping () -> Void = {}
    ) throws -> ConnectionSession {
        let identity = try DeviceIdentity.loadOrCreate(tag: "kr.jjw.graft.test.session.\(UUID().uuidString)")
        let service = PairingService(local: identity, paired: paired)
        return ConnectionSession(service: service, send: send, close: close)
    }

    func testPairedPeerReachesReady() throws {
        let session = try makeSession(paired: [phone])
        session.peerFingerprint = phone
        session.start(now: now)

        let actions = session.handle(
            envelope: Envelope(type: "hello", payload: .object([:])),
            now: now
        )

        XCTAssertEqual(session.phase, .ready)
        XCTAssertTrue(actions.contains(.sendHelloOk))
        XCTAssertTrue(actions.contains(.startHeartbeat))
    }

    func testUnpairedPeerIsClosedAfterANonPairMessage() throws {
        var closed = false
        let session = try makeSession(close: { closed = true })
        session.peerFingerprint = "stranger"
        session.start(now: now)

        // No pairing attempt: a hello from an unpaired peer is refused and the socket is closed.
        let actions = session.handle(
            envelope: Envelope(type: "hello", payload: .object([:])),
            now: now
        )

        XCTAssertEqual(session.phase, .closing)
        XCTAssertTrue(actions.contains(.closeConnection))
        XCTAssertTrue(closed, "a closing session must close the transport")
    }

    func testSilenceForSixtySecondsClosesTheConnection() throws {
        var closed = false
        let session = try makeSession(paired: [phone], close: { closed = true })
        session.peerFingerprint = phone
        session.start(now: now)
        _ = session.handle(envelope: Envelope(type: "hello", payload: .object([:])), now: now)

        session.tick(now: now.addingTimeInterval(19))
        XCTAssertFalse(closed, "19 seconds is quiet, not dead")

        session.tick(now: now.addingTimeInterval(60))
        XCTAssertTrue(closed, "60 seconds of silence must close")
        XCTAssertEqual(session.phase, .disconnected)
    }

    func testInboundFramesCountAsLiveness() throws {
        var closed = false
        let session = try makeSession(paired: [phone], close: { closed = true })
        session.peerFingerprint = phone
        session.start(now: now)
        _ = session.handle(envelope: Envelope(type: "hello", payload: .object([:])), now: now)

        // Keep sending ping every 20s; the connection must survive past the 60s mark.
        for offset in stride(from: 20.0, through: 100.0, by: 20.0) {
            session.tick(now: now.addingTimeInterval(offset))
            _ = session.handle(envelope: Envelope(type: "ping", payload: .object([:])), now: now.addingTimeInterval(offset))
        }

        XCTAssertFalse(closed)
    }

    func testPingIsSentEveryTwentySeconds() throws {
        var sent: [String] = []
        let session = try makeSession(paired: [phone], send: { sent.append($0.type) })
        session.peerFingerprint = phone
        session.start(now: now)
        _ = session.handle(envelope: Envelope(type: "hello", payload: .object([:])), now: now)

        session.tick(now: now.addingTimeInterval(19))
        XCTAssertFalse(sent.contains("ping"))

        session.tick(now: now.addingTimeInterval(20))
        XCTAssertEqual(sent.filter { $0 == "ping" }.count, 1)
    }

    func testPinMismatchNeverSchedulesReconnect() throws {
        let session = try makeSession()
        session.start(now: now)

        let actions = session.handleTLSFailure(reason: .pinMismatch, now: now)

        XCTAssertEqual(session.phase, .closed)
        XCTAssertEqual(actions, [.stopReconnect])
    }
}
