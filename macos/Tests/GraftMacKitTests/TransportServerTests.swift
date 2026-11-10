import Foundation
import Network
import Security
import XCTest
@testable import GraftMacKit
@testable import GraftProtocol

private func freePort() -> UInt16 {
    let socketFD = socket(AF_INET, SOCK_STREAM, 0)
    defer { close(socketFD) }
    var address = sockaddr_in()
    address.sin_family = sa_family_t(AF_INET)
    address.sin_addr.s_addr = inet_addr("127.0.0.1")
    address.sin_port = 0
    withUnsafePointer(to: &address) { pointer in
        _ = pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
            bind(socketFD, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    var length = socklen_t(MemoryLayout<sockaddr_in>.size)
    withUnsafeMutablePointer(to: &address) { pointer in
        _ = pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
            getsockname(socketFD, sockaddrPointer, &length)
        }
    }
    return UInt16(bigEndian: address.sin_port)
}

final class PeerAuthorizerTests: XCTestCase {
    func testRejectsUnknownPeerByDefault() {
        let authorizer = PeerAuthorizer(allowed: { [] })
        XCTAssertFalse(authorizer.isAuthorized("unrelated-fingerprint"))
    }

    func testAcceptsStoredPeer() {
        let authorizer = PeerAuthorizer(allowed: { ["pinned"] })
        XCTAssertTrue(authorizer.isAuthorized("pinned"))
    }

    func testAcceptsUnknownPeerWhilePairingIsOpen() {
        var isOpen = false
        let authorizer = PeerAuthorizer(allowed: { [] }, isPairingOpen: { isOpen })

        XCTAssertFalse(authorizer.isAuthorized("new-phone"))
        isOpen = true
        XCTAssertTrue(authorizer.isAuthorized("new-phone"))
    }

    func testWindowClosesWhenPairingCloses() {
        var isOpen = true
        let authorizer = PeerAuthorizer(allowed: { [] }, isPairingOpen: { isOpen })
        XCTAssertTrue(authorizer.isAuthorized("new-phone"))

        isOpen = false
        XCTAssertFalse(authorizer.isAuthorized("new-phone"), "a consumed token must stop admitting peers")
    }
}

final class TransportServerTests: XCTestCase {
    private func identities() throws -> (server: DeviceIdentity, peer: DeviceIdentity, tags: (String, String)) {
        let serverTag = "kr.jjw.graft.test.server.\(UUID().uuidString)"
        let peerTag = "kr.jjw.graft.test.client.\(UUID().uuidString)"
        return (
            try DeviceIdentity.loadOrCreate(tag: serverTag),
            try DeviceIdentity.loadOrCreate(tag: peerTag),
            (serverTag, peerTag)
        )
    }

    func testPeerCanSendAndReceiveAHello() throws {
        let (serverIdentity, peerIdentity, tags) = try identities()
        defer {
            DeviceIdentity.delete(tag: tags.0)
            DeviceIdentity.delete(tag: tags.1)
        }

        let authorizer = PeerAuthorizer(allowed: { [peerIdentity.fingerprint] })
        let server = TransportServer(identity: serverIdentity, authorizer: authorizer, port: freePort())

        let received = expectation(description: "server received hello and replied")
        server.onConnection = { connection in
            connection.receive { envelope in
                XCTAssertEqual(envelope.type, "hello")
                try? connection.send(Envelope(type: "hello.ok", payload: .object([:])))
                received.fulfill()
            }
        }
        try server.start()
        defer { server.stop() }

        let reply = expectation(description: "peer received hello.ok")
        let connected = expectation(description: "peer connected")
        let peer = try TransportPeer(
            identity: peerIdentity,
            host: "127.0.0.1",
            port: try XCTUnwrap(server.port),
            expectedServerFingerprint: serverIdentity.fingerprint
        )
        peer.receive { envelope in
            XCTAssertEqual(envelope.type, "hello.ok")
            reply.fulfill()
        }
        peer.start { result in
            switch result {
            case .success:
                try? peer.send(Envelope(type: "hello", payload: .object([:])))
                connected.fulfill()
            case .failure(let error):
                XCTFail("peer failed to connect: \(error)")
            }
        }

        wait(for: [connected, received, reply], timeout: 10)
    }

    func testServerRejectsUnknownClientFingerprint() throws {
        let (serverIdentity, strangerIdentity, tags) = try identities()
        defer {
            DeviceIdentity.delete(tag: tags.0)
            DeviceIdentity.delete(tag: tags.1)
        }

        let authorizer = PeerAuthorizer(allowed: { [] })
        let server = TransportServer(identity: serverIdentity, authorizer: authorizer, port: freePort())

        let accepted = expectation(description: "server must not accept")
        accepted.isInverted = true
        server.onConnection = { _ in accepted.fulfill() }
        try server.start()
        defer { server.stop() }

        let failed = expectation(description: "peer rejected")
        let peer = try TransportPeer(
            identity: strangerIdentity,
            host: "127.0.0.1",
            port: try XCTUnwrap(server.port),
            expectedServerFingerprint: serverIdentity.fingerprint
        )
        peer.start { result in
            if case .failure = result {
                failed.fulfill()
            } else {
                XCTFail("an unauthorized peer must not reach ready")
            }
        }

        wait(for: [failed, accepted], timeout: 10)
    }

    func testStopFromInsideAConnectionCallbackDoesNotCrash() throws {
        let (serverIdentity, peerIdentity, tags) = try identities()
        defer {
            DeviceIdentity.delete(tag: tags.0)
            DeviceIdentity.delete(tag: tags.1)
        }

        let authorizer = PeerAuthorizer(allowed: { [peerIdentity.fingerprint] })
        let server = TransportServer(identity: serverIdentity, authorizer: authorizer, port: freePort())

        let stopped = expectation(description: "stop ran inside the callback without crashing")
        server.onConnection = { _ in
            // Callbacks run on the server queue; stop() must not dispatch_sync onto it.
            server.stop()
            stopped.fulfill()
        }
        try server.start()

        let connected = expectation(description: "peer connected")
        let peer = try TransportPeer(
            identity: peerIdentity,
            host: "127.0.0.1",
            port: try XCTUnwrap(server.port),
            expectedServerFingerprint: serverIdentity.fingerprint
        )
        peer.start { _ in connected.fulfill() }
        defer { peer.close() }

        wait(for: [connected, stopped], timeout: 10)
    }
}
