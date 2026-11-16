import CryptoKit
import Foundation
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

final class TransferCoordinatorTests: XCTestCase {
    private var directory: URL!
    private var sourceDirectory: URL!
    private var sentControl: [Envelope] = []
    private var sentBinary: [Data] = []

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-coord-\(UUID().uuidString)", isDirectory: true)
        sourceDirectory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-src-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: sourceDirectory, withIntermediateDirectories: true)
        sentControl = []
        sentBinary = []
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.removeItem(at: sourceDirectory)
    }

    private func coordinator(
        paired: Bool = true,
        mode: TransferCoordinator.Mode = .both,
        records: TransferRecordStore? = nil
    ) -> TransferCoordinator {
        TransferCoordinator(
            directory: directory,
            mode: mode,
            isPaired: { paired },
            sendControl: { [weak self] envelope in self?.sentControl.append(envelope) },
            sendBinary: { [weak self] data in self?.sentBinary.append(data) },
            records: records
        )
    }

    private func recordStore() -> TransferRecordStore {
        TransferRecordStore(file: directory.appendingPathComponent("transfers.json"))
    }

    private func payload(_ count: Int) -> Data {
        Data((0..<count).map { UInt8($0 % 251) })
    }

    private func offerEnvelope(tid: UInt32, name: String, size: Int) -> Envelope {
        Envelope(type: "file.offer", payload: .object([
            "tid": .number(Double(tid)),
            "name": .string(name),
            "size": .number(Double(size)),
            "mime": .string("application/octet-stream")
        ]))
    }

    // MARK: - Receiving

    func testAutoAcceptsFromAPairedPeer() throws {
        let data = payload(100 * 1024)
        let coordinator = coordinator()

        coordinator.onControl(offerEnvelope(tid: 1, name: "incoming.bin", size: data.count))

        let accept = try XCTUnwrap(sentControl.last)
        XCTAssertEqual(accept.type, "file.accept")

        for chunk in stride(from: 0, to: data.count, by: 64 * 1024) {
            let end = min(chunk + 64 * 1024, data.count)
            let frame = try BinaryFrame(
                transferId: 1,
                offset: UInt64(chunk),
                data: data.subdata(in: chunk..<end)
            )
            coordinator.onBinary(frame.encoded())
        }

        let hash = Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined()
        coordinator.onControl(
            Envelope(type: "file.complete", payload: .object([
                "tid": .number(1),
                "sha256": .string(hash)
            ]))
        )

        XCTAssertTrue(coordinator.completed.contains(1))
        XCTAssertEqual(
            try Data(contentsOf: directory.appendingPathComponent("incoming.bin")),
            data
        )
        XCTAssertEqual(coordinator.progress[1]?.fraction, 1.0)
    }

    func testRejectsAnOfferFromAnUnpairedPeer() {
        let coordinator = coordinator(paired: false)

        coordinator.onControl(offerEnvelope(tid: 1, name: "incoming.bin", size: 10))

        let reply = sentControl.last
        XCTAssertEqual(reply?.type, "error")
        XCTAssertEqual(reply?.payload.objectValue?["code"]?.stringValue, "unpaired")
        XCTAssertTrue(try! FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    func testSecondOfferWithTheSameTidIsRefused() throws {
        let coordinator = coordinator()

        coordinator.onControl(offerEnvelope(tid: 1, name: "a.bin", size: 10))
        coordinator.onControl(offerEnvelope(tid: 1, name: "b.bin", size: 10))

        let last = try XCTUnwrap(sentControl.last)
        XCTAssertEqual(last.type, "error")
        XCTAssertEqual(last.payload.objectValue?["code"]?.stringValue, "transfer_not_found")
    }

    func testUnsafeNameIsRejected() throws {
        let coordinator = coordinator()

        coordinator.onControl(offerEnvelope(tid: 1, name: "../../evil.sh", size: 10))

        let last = try XCTUnwrap(sentControl.last)
        XCTAssertEqual(last.type, "error")
        XCTAssertEqual(coordinator.failures[1], .unsafeName)
    }

    func testHashMismatchIsReported() throws {
        let data = payload(1024)
        let coordinator = coordinator()
        coordinator.onControl(offerEnvelope(tid: 1, name: "bad.bin", size: data.count))
        coordinator.onBinary(try BinaryFrame(transferId: 1, offset: 0, data: data).encoded())

        coordinator.onControl(
            Envelope(type: "file.complete", payload: .object([
                "tid": .number(1),
                "sha256": .string(String(repeating: "0", count: 64))
            ]))
        )

        XCTAssertEqual(coordinator.failures[1], .hashMismatch)
        XCTAssertEqual(sentControl.last?.payload.objectValue?["code"]?.stringValue, "hash_mismatch")
    }

    func testWrongOffsetChunkFailsTheTransfer() throws {
        let coordinator = coordinator()
        coordinator.onControl(offerEnvelope(tid: 1, name: "offset.bin", size: 1024))

        coordinator.onBinary(try BinaryFrame(transferId: 1, offset: 512, data: payload(10)).encoded())

        XCTAssertEqual(coordinator.failures[1], .wrongOffset)
    }

    func testPartialFileFromAnEarlierAttemptIsResumed() throws {
        let data = payload(120 * 1024)
        let half = 64 * 1024

        // First attempt: half the file arrives, then the connection drops.
        let store = recordStore()
        let first = coordinator(records: store)
        first.onControl(offerEnvelope(tid: 1, name: "resume-me.bin", size: data.count))
        first.onBinary(try BinaryFrame(transferId: 1, offset: 0, data: data.subdata(in: 0..<half)).encoded())

        // Second attempt on a fresh connection must continue, not re-send from zero and not
        // create "resume-me (2).bin".
        sentControl = []
        let second = coordinator(records: store)
        second.onControl(offerEnvelope(tid: 2, name: "resume-me.bin", size: data.count))

        let accept = try XCTUnwrap(sentControl.last)
        XCTAssertEqual(accept.type, "file.accept")
        let offset = try XCTUnwrap(accept.payload.objectValue?["offset"]?.numberValue)
        XCTAssertEqual(UInt64(offset), UInt64(half), "the receiver must report the bytes it already has")

        second.onBinary(
            try BinaryFrame(transferId: 2, offset: UInt64(half), data: data.subdata(in: half..<data.count)).encoded()
        )
        let hash = Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined()
        second.onControl(
            Envelope(type: "file.complete", payload: .object([
                "tid": .number(2),
                "sha256": .string(hash)
            ]))
        )

        XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("resume-me.bin")), data)
    }

    func testCompletedFileWithTheSameNameGetsASuffix() throws {
        let data = payload(1024)
        let coordinator = coordinator()

        for tid in [UInt32(1), UInt32(2)] {
            coordinator.onControl(offerEnvelope(tid: tid, name: "twice.bin", size: data.count))
            coordinator.onBinary(try BinaryFrame(transferId: tid, offset: 0, data: data).encoded())
            let hash = Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined()
            coordinator.onControl(
                Envelope(type: "file.complete", payload: .object([
                    "tid": .number(Double(tid)),
                    "sha256": .string(hash)
                ]))
            )
        }

        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        XCTAssertEqual(names, ["twice (2).bin", "twice.bin"])
    }

    func testPartialFileWithoutARecordIsNotResumed() throws {
        let data = payload(120 * 1024)
        let half = 64 * 1024

        let first = coordinator()
        first.onControl(offerEnvelope(tid: 1, name: "orphan.bin", size: data.count))
        first.onBinary(try BinaryFrame(transferId: 1, offset: 0, data: data.subdata(in: 0..<half)).encoded())

        sentControl = []
        let second = coordinator()
        second.onControl(offerEnvelope(tid: 2, name: "orphan.bin", size: data.count))

        let offset = try XCTUnwrap(sentControl.last?.payload.objectValue?["offset"]?.numberValue)
        XCTAssertEqual(UInt64(offset), 0, "a leftover file with no record must not be resumed")
    }

    func testHashMismatchClearsTheRecord() throws {
        let store = recordStore()
        let data = payload(1024)
        let coordinator = coordinator(records: store)

        coordinator.onControl(offerEnvelope(tid: 1, name: "bad.bin", size: data.count))
        coordinator.onBinary(try BinaryFrame(transferId: 1, offset: 0, data: data).encoded())
        XCTAssertEqual(store.count(), 1)

        coordinator.onControl(
            Envelope(type: "file.complete", payload: .object([
                "tid": .number(1),
                "sha256": .string(String(repeating: "0", count: 64))
            ]))
        )

        XCTAssertEqual(store.count(), 0, "a corrupt partial must not stay resumable")
    }

    func testCompletedTransferClearsTheRecord() throws {
        let store = recordStore()
        let data = payload(1024)
        let coordinator = coordinator(records: store)

        coordinator.onControl(offerEnvelope(tid: 1, name: "done.bin", size: data.count))
        coordinator.onBinary(try BinaryFrame(transferId: 1, offset: 0, data: data).encoded())
        let hash = Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined()
        coordinator.onControl(
            Envelope(type: "file.complete", payload: .object([
                "tid": .number(1),
                "sha256": .string(hash)
            ]))
        )

        XCTAssertEqual(store.count(), 0)
    }

    // MARK: - Sending

    func testOfferSendsControlAndFramesOnAccept() throws {
        let data = payload(100 * 1024)
        let url = sourceDirectory.appendingPathComponent("outgoing.bin")
        try data.write(to: url)

        let coordinator = coordinator()
        try coordinator.offer(url: url, transferId: 5)

        XCTAssertEqual(sentControl.last?.type, "file.offer")

        coordinator.onControl(
            Envelope(type: "file.accept", payload: .object([
                "tid": .number(5),
                "offset": .number(0)
            ]))
        )

        XCTAssertEqual(sentBinary.count, 2)
        XCTAssertEqual(sentControl.last?.type, "file.complete")

        let hash = sentControl.last?.payload.objectValue?["sha256"]?.stringValue
        XCTAssertEqual(hash, Data(SHA256.hash(data: data)).map { String(format: "%02x", $0) }.joined())
    }

    func testDuplicateSendIdIsRefused() throws {
        let url = sourceDirectory.appendingPathComponent("dup.bin")
        try payload(10).write(to: url)

        let coordinator = coordinator()
        try coordinator.offer(url: url, transferId: 5)

        XCTAssertThrowsError(try coordinator.offer(url: url, transferId: 5)) {
            XCTAssertEqual($0 as? TransferFailure, .duplicateTransfer)
        }
    }
}
