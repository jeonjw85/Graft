import XCTest
@testable import GraftMacKit

final class TransferRecordStoreTests: XCTestCase {
    private var file: URL!
    private var now: Int64 = 1_762_752_000_000

    override func setUpWithError() throws {
        file = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-records-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("transfers.json")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
    }

    private func store() -> TransferRecordStore {
        TransferRecordStore(file: file, clock: { self.now })
    }

    private func record(
        name: String = "보고서.pdf",
        size: UInt64 = 1024,
        confirmed: UInt64 = 512,
        directory: String = "/tmp/graft",
        at: Int64? = nil
    ) -> ResumeRecord {
        ResumeRecord(
            name: name,
            directory: directory,
            size: size,
            bytesConfirmed: confirmed,
            updatedAtMillis: at ?? now
        )
    }

    func testRoundTripsARecord() throws {
        store().upsert(record())

        let found = store().find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512)
        XCTAssertEqual(found?.bytesConfirmed, 512)
        XCTAssertEqual(found?.size, 1024)
    }

    func testFreshStoreReadsWhatAnotherWrote() {
        store().upsert(record())
        XCTAssertEqual(store().count(), 1)
    }

    func testExpiredRecordsAreIgnoredAndDeleted() {
        let store = store()
        store.upsert(record(at: now))

        now += 25 * 60 * 60 * 1000

        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512))
        XCTAssertEqual(store.count(), 0, "an expired record must be deleted, not merely ignored")
    }

    func testPurgeRemovesOnlyExpiredRecords() {
        let store = store()
        store.upsert(record(name: "old.bin", at: now - 25 * 60 * 60 * 1000))
        store.upsert(record(name: "new.bin"))

        XCTAssertEqual(store.purgeExpired(), 1)
        XCTAssertEqual(store.count(), 1)
    }

    func testDifferentNameOrSizeDoesNotMatch() {
        let store = store()
        store.upsert(record())

        XCTAssertNil(store.find(name: "other.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512))
        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 2048, partialBytesOnDisk: 512))
        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/other", size: 1024, partialBytesOnDisk: 512))
    }

    func testPartialFileOfTheWrongLengthDoesNotMatch() {
        let store = store()
        store.upsert(record(confirmed: 1024))

        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512))
    }

    func testZeroBytesOnDiskDoesNotMatch() {
        let store = store()
        store.upsert(record(confirmed: 0))

        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 0))
    }

    func testRemoveClearsTheRecord() {
        let store = store()
        store.upsert(record())

        store.remove(name: "보고서.pdf", directory: "/tmp/graft")

        XCTAssertNil(store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512))
        XCTAssertEqual(store.count(), 0)
    }

    func testUpsertReplacesTheSameNameAndDirectory() {
        let store = store()
        store.upsert(record(confirmed: 256))
        store.upsert(record(confirmed: 512))

        XCTAssertEqual(store.count(), 1)
        XCTAssertEqual(
            store.find(name: "보고서.pdf", directory: "/tmp/graft", size: 1024, partialBytesOnDisk: 512)?.bytesConfirmed,
            512
        )
    }

    func testJsonShapeIsSharedWithAndroid() throws {
        store().upsert(record())

        let data = try Data(contentsOf: file)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let records = try XCTUnwrap(root["records"] as? [[String: Any]])
        let first = try XCTUnwrap(records.first)

        XCTAssertEqual(first["name"] as? String, "보고서.pdf")
        XCTAssertEqual(first["directory"] as? String, "/tmp/graft")
        XCTAssertEqual(first["size"] as? UInt64, 1024)
        XCTAssertEqual(first["bytesConfirmed"] as? UInt64, 512)
        XCTAssertEqual(first["updatedAtMillis"] as? Int64, now)
    }
}
