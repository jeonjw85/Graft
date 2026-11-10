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

    private func coordinator(paired: Bool = true, mode: TransferCoordinator.Mode = .both) -> TransferCoordinator {
        TransferCoordinator(
            directory: directory,
            mode: mode,
            isPaired: { paired },
            sendControl: { [weak self] envelope in self?.sentControl.append(envelope) },
            sendBinary: { [weak self] data in self?.sentBinary.append(data) }
        )
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
