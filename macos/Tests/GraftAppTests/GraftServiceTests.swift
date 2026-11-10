import XCTest
@testable import GraftApp

@MainActor
final class GraftServiceTests: XCTestCase {
    func testPortInUseReportsFailure() throws {
        let port = try occupyFreePort()
        defer { close(port.socket) }

        let service = GraftService(port: port.number, storeURL: temporaryStoreURL())

        do {
            try service.start()
            XCTFail("binding an occupied port must throw")
        } catch {
            XCTAssertEqual(service.menuState.statusLabel, "Port \(port.number) is in use")
        }
    }

    func testStartReturnsPairingURIWhilePairing() throws {
        let port = try occupyFreePort()
        close(port.socket)

        let service = GraftService(port: port.number, storeURL: temporaryStoreURL())
        try service.start()
        defer { service.stop() }

        service.beginPairing()

        let uri = try XCTUnwrap(service.pairingURI)
        XCTAssertTrue(uri.hasPrefix("graft://pair?v=1"))
        XCTAssertEqual(service.menuState.status, .pairing)
    }

    func testForgetPeerRemovesItFromTheStore() throws {
        let url = temporaryStoreURL()
        let store = PeerStore(url: url)
        try store.add(PairedPeer(fingerprint: "fp", name: "Phone", platform: "android", pairedAtMillis: 1))

        let service = GraftService(port: 47999, storeURL: url)
        service.reloadPeers()
        XCTAssertEqual(service.peers.count, 1)

        service.forgetPeer(fingerprint: "fp")

        XCTAssertTrue(PeerStore(url: url).load().isEmpty)
    }

    func testPairingURIExpiresAfterTwoMinutes() throws {
        let port = try occupyFreePort()
        close(port.socket)

        let now = Date(timeIntervalSince1970: 1_762_752_000)
        let service = GraftService(port: port.number, storeURL: temporaryStoreURL(), clock: { now })
        try service.start()
        defer { service.stop() }

        service.beginPairing()
        XCTAssertNotNil(service.pairingURI)

        service.refreshPairingState(now: now.addingTimeInterval(121))
        XCTAssertNil(service.pairingURI)
        XCTAssertEqual(service.menuState.status, .idle)
    }

    func testForgetClearsThePendingPairingRecord() throws {
        // persistPairingIfNeeded runs on every inbound frame. If forgetting a peer leaves the last
        // accepted pairing in place, the next frame silently re-adds the device the user removed.
        let url = temporaryStoreURL()
        let store = PeerStore(url: url)
        try store.add(PairedPeer(fingerprint: "fp", name: "Phone", platform: "android", pairedAtMillis: 1))

        let service = GraftService(port: 47998, storeURL: url)
        service.reloadPeers()
        service.forgetPeer(fingerprint: "fp")

        XCTAssertTrue(service.peers.isEmpty)
        XCTAssertNil(
            service.pendingPairedPeer,
            "forgetting must clear the pending record so it cannot be written back"
        )
    }

    // MARK: - helpers

    private func temporaryStoreURL() -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-service-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("peers.json")
    }

    private struct BoundPort {
        let socket: Int32
        let number: UInt16
    }

    private func occupyFreePort() throws -> BoundPort {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(socketFD, 0)

        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0

        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { throw XCTSkip("could not bind a probe socket") }

        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(socketFD, $0, &length)
            }
        }
        return BoundPort(socket: socketFD, number: UInt16(bigEndian: address.sin_port))
    }

    private func close(_ socketFD: Int32) {
        Darwin.close(socketFD)
    }
}
