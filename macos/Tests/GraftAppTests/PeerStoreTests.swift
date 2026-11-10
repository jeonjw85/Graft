import XCTest
@testable import GraftApp

final class PeerStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-peers-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private var url: URL { directory.appendingPathComponent("peers.json") }

    private func peer(_ fingerprint: String, name: String = "Galaxy S24") -> PairedPeer {
        PairedPeer(fingerprint: fingerprint, name: name, platform: "android", pairedAtMillis: 1_762_752_000_000)
    }

    func testRoundTrip() throws {
        let store = PeerStore(url: url)
        try store.add(peer("fp-one"))

        XCTAssertEqual(PeerStore(url: url).load(), [peer("fp-one")])
    }

    func testAddReplacesSameFingerprint() throws {
        let store = PeerStore(url: url)
        try store.add(peer("fp-one", name: "Old name"))
        try store.add(peer("fp-one", name: "New name"))

        XCTAssertEqual(store.load().count, 1)
        XCTAssertEqual(store.load().first?.name, "New name")
    }

    func testRemoveLeavesOthers() throws {
        let store = PeerStore(url: url)
        try store.add(peer("fp-one"))
        try store.add(peer("fp-two", name: "Pixel"))

        try store.remove(fingerprint: "fp-one")

        XCTAssertEqual(store.load().map(\.fingerprint), ["fp-two"])
    }

    func testFilePermissionsAreOwnerOnly() throws {
        try PeerStore(url: url).add(peer("fp-one"))

        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
        XCTAssertEqual(permissions & 0o777, 0o600)
    }

    func testCorruptFileLoadsAsEmptyAndIsPreserved() throws {
        try Data("not json".utf8).write(to: url)

        let store = PeerStore(url: url)

        XCTAssertEqual(store.load(), [])
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: url.path + ".corrupt"),
            "the unreadable file must be preserved for recovery"
        )
    }

    func testEmptyFileLoadsAsEmptyWithoutCreatingCorruptCopy() throws {
        try Data().write(to: url)

        XCTAssertEqual(PeerStore(url: url).load(), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path + ".corrupt"))
    }

    func testMissingFileLoadsAsEmpty() {
        XCTAssertEqual(PeerStore(url: url).load(), [])
    }

    func testJsonShapeMatchesTheAndroidContract() throws {
        let store = PeerStore(url: url)
        try store.add(peer("fp-one"))

        let data = try Data(contentsOf: url)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let peers = try XCTUnwrap(object["peers"] as? [[String: Any]])
        let first = try XCTUnwrap(peers.first)

        XCTAssertEqual(first["fingerprint"] as? String, "fp-one")
        XCTAssertEqual(first["name"] as? String, "Galaxy S24")
        XCTAssertEqual(first["platform"] as? String, "android")
        XCTAssertEqual(first["pairedAtMillis"] as? Int64, 1_762_752_000_000)
    }
}
