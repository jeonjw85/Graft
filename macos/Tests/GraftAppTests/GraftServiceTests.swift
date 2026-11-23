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

    func testPrivacyTogglesSurviveARestart() {
        let suite = "graft.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }

        let first = GraftService(
            storeURL: temporaryStoreURL(),
            identityTag: tag,
            defaults: defaults
        )
        first.updateNotificationPreview(enabled: false)
        first.autoCopyClipboard = false

        let second = GraftService(
            storeURL: temporaryStoreURL(),
            identityTag: tag,
            defaults: defaults
        )

        XCTAssertFalse(second.notificationPreviews)
        XCTAssertFalse(second.autoCopyClipboard)
    }

    func testNotificationPreviewsAreHiddenUntilTurnedOn() {
        let suite = "graft.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }

        let service = GraftService(
            storeURL: temporaryStoreURL(),
            identityTag: tag,
            defaults: defaults
        )

        XCTAssertFalse(service.notificationPreviews)
        XCTAssertTrue(service.autoCopyClipboard)
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

    func testLocalAddressPrefersPhysicalLanOverVpnAndDocker() {
        let selected = GraftService.selectLocalAddress(from: [
            (name: "lo0", ip: "127.0.0.1"),
            (name: "en0", ip: "192.168.1.42"),
            (name: "bridge100", ip: "192.168.64.1"),
            (name: "utun4", ip: "100.64.0.12"),
            (name: "awdl0", ip: "169.254.10.1"),
        ])
        XCTAssertEqual(selected, "192.168.1.42")

        let ethernetFallback = GraftService.selectLocalAddress(from: [
            (name: "utun3", ip: "10.8.0.2"),
            (name: "en6", ip: "192.168.0.15"),
        ])
        XCTAssertEqual(ethernetFallback, "192.168.0.15")
    }

    func testAutoShareClipboardSurvivesARestart() {
        let suite = "graft.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }

        let first = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        first.autoShareClipboard = false

        let second = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        XCTAssertFalse(second.autoShareClipboard)
    }

    func testCustomReceiveDirectorySurvivesARestart() {
        let suite = "graft.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }
        let custom = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-custom-dir-\(UUID().uuidString)", isDirectory: true)

        let first = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        first.updateReceiveDirectory(custom)

        let second = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        XCTAssertEqual(second.receiveDirectory.path, custom.path)
    }

    func testZipDirectoryCompressesAFolderIntoAZipArchive() throws {
        let folder = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-folder-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("hello folder".utf8).write(to: folder.appendingPathComponent("note.txt"))

        let result = try XCTUnwrap(GraftService.zipDirectory(folder))
        defer { try? FileManager.default.removeItem(at: result.container) }

        XCTAssertEqual(result.archive.lastPathComponent, "\(folder.lastPathComponent).zip")
        let size = (try FileManager.default.attributesOfItem(atPath: result.archive.path)[.size] as? NSNumber)?.intValue ?? 0
        XCTAssertGreaterThan(size, 0)
    }

    func testScreenshotWatcherDetectsOnlyNewScreenshots() throws {
        let dir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-shots-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        try Data("old".utf8).write(to: dir.appendingPathComponent("Screenshot 2026-11-23 at 10.00.00.png"))
        let watcher = ScreenshotWatcher(directoryProvider: { dir })
        watcher.baseline()
        XCTAssertTrue(watcher.pollNewScreenshots().isEmpty)

        // Unrelated file & zero-byte placeholder must be ignored.
        try Data("notes".utf8).write(to: dir.appendingPathComponent("notes.png"))
        try Data().write(to: dir.appendingPathComponent("Screenshot 2026-11-23 at 10.01.00.png"))
        XCTAssertTrue(watcher.pollNewScreenshots().isEmpty)

        // Finished Korean & English screenshots are emitted once each.
        let korean = dir.appendingPathComponent("스크린샷 2026-11-23 오후 2.10.00.png")
        try Data("png-bytes".utf8).write(to: korean)
        try Data("png-bytes".utf8).write(to: dir.appendingPathComponent("Screenshot 2026-11-23 at 10.01.00.png"))

        let detected = watcher.pollNewScreenshots().map(\.lastPathComponent).sorted()
        XCTAssertEqual(detected, [
            "Screenshot 2026-11-23 at 10.01.00.png",
            "스크린샷 2026-11-23 오후 2.10.00.png",
        ])
        XCTAssertTrue(watcher.pollNewScreenshots().isEmpty)
    }

    func testAutoSendScreenshotsDefaultsToOffAndPersists() {
        let suite = "graft.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let tag = temporaryIdentityTag()
        defer { DeviceIdentity.delete(tag: tag) }

        let first = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        XCTAssertFalse(first.autoSendScreenshots)
        first.autoSendScreenshots = true

        let second = GraftService(storeURL: temporaryStoreURL(), identityTag: tag, defaults: defaults)
        XCTAssertTrue(second.autoSendScreenshots)
    }
}
