import Foundation
import XCTest
@testable import GraftProtocol

final class TransferStateTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_762_752_000)

    private func record(offset: UInt64 = 0) -> TransferRecord {
        TransferRecord(
            transferId: 7,
            phase: .idle,
            name: "보고서.pdf",
            size: 200_000,
            bytesConfirmed: offset,
            sha256: nil,
            direction: .receive,
            startedAt: start
        )
    }

    // MARK: - Fixture rows

    func testEveryTransitionRow() throws {
        let fixture = try TransferTransitions.load()
        XCTAssertEqual(fixture.rows.count, 12)

        for row in fixture.rows {
            var machine = TransferMachine(record: record())
            machine.forcePhase(row.from)

            let event = try row.makeEvent(bytesConfirmed: machine.record.bytesConfirmed)
            let actions = machine.handle(event)

            XCTAssertEqual(machine.phase, row.to, "\(row.from) --\(row.event)--> \(row.to)")
            XCTAssertEqual(
                describe(actions),
                row.actions,
                "\(row.from) --\(row.event)--> actions"
            )
        }
    }

    // MARK: - Offset rules

    func testChunkAtTheWrongOffsetFails() {
        var machine = TransferMachine(record: record())
        machine.handle(.offer)
        machine.handle(.accept(offset: 0))
        XCTAssertEqual(machine.phase, .receiving)

        let actions = machine.handle(.chunk(offset: 64 * 1024, length: 64))

        XCTAssertEqual(machine.phase, .failed)
        XCTAssertEqual(describe(actions), ["abort"])
        XCTAssertEqual(machine.record.bytesConfirmed, 0, "a rejected chunk must not advance the offset")
    }

    func testChunksAdvanceTheConfirmedOffset() {
        var machine = TransferMachine(record: record())
        _ = machine.handle(.offer)
        _ = machine.handle(.accept(offset: 0))

        _ = machine.handle(.chunk(offset: 0, length: 64 * 1024))
        XCTAssertEqual(machine.record.bytesConfirmed, 64 * 1024)

        _ = machine.handle(.chunk(offset: 64 * 1024, length: 64 * 1024))
        XCTAssertEqual(machine.record.bytesConfirmed, 128 * 1024)
    }

    func testResumeSeeksToTheAcceptedOffset() {
        var machine = TransferMachine(record: record())
        _ = machine.handle(.offer)

        let actions = machine.handle(.accept(offset: 1024))

        XCTAssertEqual(machine.phase, .receiving)
        XCTAssertEqual(machine.record.bytesConfirmed, 1024)
        XCTAssertEqual(
            actions.compactMap { action -> UInt64? in
                if case .seekTo(let offset) = action { return offset }
                return nil
            },
            [1024]
        )
    }

    func testResumeRejectsASeekBackwards() {
        var machine = TransferMachine(record: record(offset: 4096))
        _ = machine.handle(.offer)

        _ = machine.handle(.accept(offset: 1024))

        XCTAssertEqual(machine.phase, .failed, "resuming behind confirmed bytes would duplicate data")
    }

    func testChunkAfterCompletionIsIgnored() {
        var machine = TransferMachine(record: record())
        _ = machine.handle(.offer)
        _ = machine.handle(.accept(offset: 0))
        _ = machine.handle(.complete)
        _ = machine.handle(.hashOk)
        XCTAssertEqual(machine.phase, .completed)

        let actions = machine.handle(.chunk(offset: 0, length: 10))

        XCTAssertEqual(machine.phase, .completed)
        XCTAssertTrue(actions.isEmpty)
    }

    // MARK: - Stall

    func testThirtySecondsOfSilenceFailsTheTransfer() {
        var machine = TransferMachine(record: record())
        _ = machine.handle(.offer)
        _ = machine.handle(.accept(offset: 0))

        machine.tick(now: start.addingTimeInterval(29))
        XCTAssertEqual(machine.phase, .receiving, "29 seconds is slow, not stalled")

        let actions = machine.tick(now: start.addingTimeInterval(30))
        XCTAssertEqual(machine.phase, .failed)
        XCTAssertEqual(describe(actions), ["abort"])
    }

    func testAChunkResetsTheStallClock() {
        var machine = TransferMachine(record: record())
        _ = machine.handle(.offer)
        _ = machine.handle(.accept(offset: 0))

        machine.tick(now: start.addingTimeInterval(20))
        _ = machine.handle(.chunk(offset: 0, length: 1024))
        machine.tick(now: start.addingTimeInterval(45))

        XCTAssertEqual(machine.phase, .receiving, "the chunk at t=20 restarts the 30 second window")
    }

    // MARK: - Helpers

    private func describe(_ actions: [TransferAction]) -> [String] {
        actions.map { action in
            if case .seekTo(let offset) = action { return "seekTo(\(offset))" }
            return "\(action)"
        }
    }
}

// MARK: - Fixture

struct TransferTransitions {
    struct Row {
        let from: TransferPhase
        let event: String
        let offset: String?
        let length: Int?
        let to: TransferPhase
        let actions: [String]

        func makeEvent(bytesConfirmed: UInt64) throws -> TransferEvent {
            switch event {
            case "offer":
                return .offer
            case "accept":
                return .accept(offset: UInt64(offset ?? "0") ?? 0)
            case "reject":
                return .reject
            case "chunk":
                let value: UInt64
                switch offset {
                case "confirmed":
                    value = bytesConfirmed
                case "mismatch":
                    value = bytesConfirmed + 64 * 1024 + 1
                default:
                    value = UInt64(offset ?? "0") ?? 0
                }
                return .chunk(offset: value, length: length ?? 0)
            case "complete":
                return .complete
            case "hashOk":
                return .hashOk
            case "hashMismatch":
                return .hashMismatch
            case "stall":
                return .stall
            case "cancel":
                return .cancel
            default:
                throw NSError(domain: "TransferTransitions", code: 1)
            }
        }
    }

    let rows: [Row]

    static func load() throws -> TransferTransitions {
        let url = FixtureLoader.directory.appendingPathComponent("transfer/transitions.json")
        let root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        )
        let rows = try (root["transitions"] as? [[String: Any]] ?? []).map { row -> Row in
            let actions: [String] = (row["actions"] as? [Any] ?? []).compactMap { action in
                if let text = action as? String { return text }
                if let object = action as? [String: Any], let seek = object["seekTo"] as? Int {
                    return "seekTo(\(seek))"
                }
                return nil
            }
            return Row(
                from: try XCTUnwrap(TransferPhase(rawValue: try XCTUnwrap(row["from"] as? String))),
                event: try XCTUnwrap(row["event"] as? String),
                offset: row["offset"].map { "\($0)" },
                length: row["length"] as? Int,
                to: try XCTUnwrap(TransferPhase(rawValue: try XCTUnwrap(row["to"] as? String))),
                actions: actions
            )
        }
        return TransferTransitions(rows: rows)
    }
}
