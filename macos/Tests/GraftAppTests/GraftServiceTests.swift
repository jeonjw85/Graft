import XCTest
@testable import GraftApp
import GraftMacKit

@MainActor
final class GraftServiceTests: XCTestCase {
    func testPortInUseReportsFailure() throws {
        let port = try occupyFreePort()
        defer { closeProbeSocket(port.socket) }

        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(port: port.number, storeURL: temporaryStoreURL(), identityTag: tag)

        do {
            try service.start()
            XCTFail("binding an occupied port must throw")
        } catch {
            XCTAssertEqual(service.menuState.statusLabel, "Port \(port.number) is in use")
        }
    }

    func testRestartRecoversAfterThePortIsFreed() throws {
        let port = try occupyFreePort()
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(port: port.number, storeURL: temporaryStoreURL(), identityTag: tag)

        XCTAssertThrowsError(try service.start())
        XCTAssertEqual(service.menuState.statusLabel, "Port \(port.number) is in use")

        closeProbeSocket(port.socket)
        defer { service.stop() }

        // "Try again" is a user action; the OS may take a moment to release the probe socket.
        var recovered = false
        for _ in 0..<10 {
            service.restart()
            if service.menuState.canPair {
                recovered = true
                break
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertTrue(recovered, "a recovered app must be able to pair")
    }

    func testStartReturnsPairingURIWhilePairing() throws {
        let port = try occupyFreePort()
        closeProbeSocket(port.socket)

        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(port: port.number, storeURL: temporaryStoreURL(), identityTag: tag)
        try service.start()
        defer { service.stop() }

        service.beginPairing()

        let uri = try XCTUnwrap(service.pairingURI)
        XCTAssertTrue(uri.hasPrefix("graft://pair?v=1"))
        XCTAssertEqual(service.menuState.status, .pairing)
    }

    func testUsingTheQRCodeAgainClearsTheTypedValues() throws {
        let port = try occupyFreePort()
        closeProbeSocket(port.socket)
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(port: port.number, storeURL: temporaryStoreURL(), identityTag: tag)
        try service.start()
        defer { service.stop() }

        service.beginManualPairing()
        XCTAssertNotNil(service.manualPairing)

        service.beginPairing()

        XCTAssertNil(service.manualPairing, "a stale code must not stay on screen")
        XCTAssertNotNil(service.qrImage)
    }

    func testForgetPeerRemovesItFromTheStore() throws {
        let url = temporaryStoreURL()
        let store = PeerStore(url: url)
        try store.add(PairedPeer(fingerprint: "fp", name: "Phone", platform: "android", pairedAtMillis: 1))

        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(port: 47999, storeURL: url, identityTag: tag)
        service.reloadPeers()
        XCTAssertEqual(service.peers.count, 1)

        service.forgetPeer(fingerprint: "fp")

        XCTAssertTrue(PeerStore(url: url).load().isEmpty)
    }

    func testPairingURIExpiresAfterTwoMinutes() throws {
        let port = try occupyFreePort()
        closeProbeSocket(port.socket)

        let now = Date(timeIntervalSince1970: 1_762_752_000)
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(
            port: port.number,
            storeURL: temporaryStoreURL(),
            clock: { now },
            identityTag: tag
        )
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
}
