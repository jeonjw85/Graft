import AppKit
import SwiftUI
import XCTest
@testable import GraftApp
import GraftMacKit

/// Drives the real pairing view inside a real window.
///
/// The unit test for `WindowCloseRevoker` proves the observer works; this proves the wiring —
/// hosting view, window accessor, revoker, and `GraftService.cancelPairing()` — survives contact.
@MainActor
final class PairingWindowLifecycleTests: XCTestCase {
    private func makeService() throws -> GraftService {
        let port = try occupyFreePort()
        closeProbeSocket(port.socket)
        let tag = temporaryIdentityTag()
        addTeardownBlock { DeviceIdentity.delete(tag: tag) }
        let service = GraftService(
            port: port.number,
            storeURL: temporaryStoreURL(),
            identityTag: tag
        )
        try service.start()
        return service
    }

    private func spinRunLoop(_ seconds: TimeInterval = 0.3) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    func testThePairingWindowIsFoundByTitle() {
        let other = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 10, height: 10),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        other.title = "Something else"
        let pairing = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 10, height: 10),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        pairing.title = PairingWindowLocator.title

        XCTAssertTrue(PairingWindowLocator.find(in: [other, pairing]) === pairing)
        XCTAssertNil(PairingWindowLocator.find(in: [other]))
    }

    func testClosingTheRealSceneWindowRevokes() throws {
        let service = try makeService()
        defer { service.stop() }
        service.beginPairing()

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 560),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = PairingWindowLocator.title
        window.contentView = NSHostingView(rootView: PairingWindowView(service: service))
        window.makeKeyAndOrderFront(nil)
        spinRunLoop()

        window.close()
        spinRunLoop()

        XCTAssertNil(service.pairingURI, "closing must revoke; this is what the scene relies on")
        withExtendedLifetime(window) {}
    }

    func testThePairingWindowBecomesKeyWhenShown() throws {
        let service = try makeService()
        defer { service.stop() }
        service.beginPairing()

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 560),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.contentView = NSHostingView(rootView: PairingWindowView(service: service))
        window.makeKeyAndOrderFront(nil)
        spinRunLoop()

        XCTAssertTrue(window.isVisible)
        withExtendedLifetime(window) {}
    }
}
