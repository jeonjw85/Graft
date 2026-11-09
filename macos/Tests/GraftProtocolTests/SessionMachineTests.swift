import Foundation
import XCTest
@testable import GraftProtocol

final class SessionMachineTests: XCTestCase {
    private func fixture() throws -> SessionTransitions {
        try SessionTransitions.load()
    }

    func testEveryTransitionRow() throws {
        let fixture = try fixture()
        XCTAssertFalse(fixture.transitions.isEmpty)

        for row in fixture.transitions {
            var machine = try SessionMachine(phase: row.from)
            let actions = machine.handle(row.event)

            XCTAssertEqual(machine.phase, row.to, "\(row.from) --\(row.event)--> \(row.to)")
            XCTAssertEqual(actions, row.actions, "\(row.from) --\(row.event)--> actions")
        }
    }

    func testPinMismatchNeverSchedulesReconnect() throws {
        let fixture = try fixture()
        let rule = fixture.pinMismatch

        for phase in rule.from {
            var machine = try SessionMachine(phase: phase)
            let actions = machine.handle(.tlsFailed(reason: .pinMismatch))

            XCTAssertEqual(machine.phase, rule.to)
            XCTAssertEqual(actions, rule.actions)
            XCTAssertFalse(actions.contains(.scheduleReconnect), "pin mismatch must not auto-retry")
            XCTAssertTrue(actions.contains(.stopReconnect))
        }
    }

    func testReconnectBackoffSequence() {
        var backoff = ReconnectBackoff(jitter: { _ in 0 })

        let delays = (0..<8).map { _ in backoff.nextDelay() }
        XCTAssertEqual(delays, [1, 2, 4, 8, 16, 32, 60, 60])
    }

    func testBackoffJitterStaysWithinTwentyPercent() {
        var backoff = ReconnectBackoff(jitter: { upperBound in upperBound })

        for base in [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0] {
            let delay = backoff.nextDelay()
            XCTAssertGreaterThanOrEqual(delay, base)
            XCTAssertLessThanOrEqual(delay, base * 1.2)
        }
    }

    func testBackoffResetsAfterSuccess() {
        var backoff = ReconnectBackoff(jitter: { _ in 0 })
        _ = backoff.nextDelay()
        _ = backoff.nextDelay()
        XCTAssertEqual(backoff.nextDelay(), 4)

        backoff.reset()
        XCTAssertEqual(backoff.nextDelay(), 1)
    }

    func testHeartbeatEmitsPingEveryTwentySeconds() {
        var heartbeat = Heartbeat(now: Date(timeIntervalSince1970: 0))

        XCTAssertFalse(heartbeat.shouldPing(now: Date(timeIntervalSince1970: 19)))
        XCTAssertTrue(heartbeat.shouldPing(now: Date(timeIntervalSince1970: 20)))
        XCTAssertFalse(heartbeat.shouldPing(now: Date(timeIntervalSince1970: 39)))
        XCTAssertTrue(heartbeat.shouldPing(now: Date(timeIntervalSince1970: 40)))
    }

    func testHeartbeatTimeout() {
        let start = Date(timeIntervalSince1970: 0)
        var heartbeat = Heartbeat(now: start)

        XCTAssertFalse(heartbeat.isDead(now: start.addingTimeInterval(19)))
        XCTAssertFalse(heartbeat.isDead(now: start.addingTimeInterval(59)))
        XCTAssertTrue(heartbeat.isDead(now: start.addingTimeInterval(60)))

        // Any inbound frame counts as liveness.
        heartbeat.noteActivity(now: start.addingTimeInterval(50))
        XCTAssertFalse(heartbeat.isDead(now: start.addingTimeInterval(100)))
        XCTAssertTrue(heartbeat.isDead(now: start.addingTimeInterval(110)))
    }

    func testUnpairedConnectionRejectsNonPairMessage() throws {
        var machine = try SessionMachine(phase: .unpaired)

        let actions = machine.handle(.messageReceived)

        XCTAssertEqual(machine.phase, .closing)
        XCTAssertEqual(actions, [.sendErrorUnpaired, .closeConnection])
    }
}

// MARK: - Fixture

struct SessionTransitions {
    struct Row {
        let from: SessionPhase
        let event: SessionEvent
        let to: SessionPhase
        let actions: [SessionAction]
    }

    struct PinMismatch {
        let from: [SessionPhase]
        let to: SessionPhase
        let actions: [SessionAction]
    }

    let transitions: [Row]
    let pinMismatch: PinMismatch

    static func load() throws -> SessionTransitions {
        let url = FixtureLoader.directory.appendingPathComponent("session/transitions.json")
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        let rows = try (root["transitions"] as? [[String: Any]] ?? []).map { row -> Row in
            let from = SessionPhase(rawValue: try XCTUnwrap(row["from"] as? String))
            let event = try SessionEvent.parse(try XCTUnwrap(row["event"] as? String))
            let to = SessionPhase(rawValue: try XCTUnwrap(row["to"] as? String))
            let actions = (row["actions"] as? [String] ?? []).map { action in
                SessionAction(rawValue: action)!
            }
            return Row(
                from: try XCTUnwrap(from),
                event: event,
                to: try XCTUnwrap(to),
                actions: actions
            )
        }

        let mismatch = try XCTUnwrap(root["pinMismatch"] as? [String: Any])
        let phases = try (mismatch["from"] as? [String] ?? []).map { name -> SessionPhase in
            try XCTUnwrap(SessionPhase(rawValue: name))
        }
        let actions = (mismatch["actions"] as? [String] ?? []).map { SessionAction(rawValue: $0)! }

        return SessionTransitions(
            transitions: rows,
            pinMismatch: PinMismatch(
                from: phases,
                to: try XCTUnwrap(SessionPhase(rawValue: try XCTUnwrap(mismatch["to"] as? String))),
                actions: actions
            )
        )
    }
}
