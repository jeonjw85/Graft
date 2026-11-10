import XCTest
@testable import GraftApp
import GraftMacKit
import GraftProtocol

/// Pairs a real TLS peer against the app's `GraftService`, which is what the UI drives.
@MainActor
final class PairingIntegrationTests: XCTestCase {
    func testPeerPairsThroughTheServiceAndIsPersisted() throws {
        let storeURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("graft-int-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("peers.json")
        defer { try? FileManager.default.removeItem(at: storeURL.deletingLastPathComponent()) }

        let port = try freePort()
        let service = GraftService(port: port, storeURL: storeURL)
        try service.start()
        defer { service.stop() }

        service.beginPairing()
        let uri = try XCTUnwrap(service.pairingURI)
        let offer = try PairingURI.parse(uri)
        XCTAssertEqual(offer.port, port)
        XCTAssertNotNil(service.qrImage, "the QR code must render for the UI")

        let peerTag = "kr.jjw.graft.test.int.\(UUID().uuidString)"
        let peerIdentity = try DeviceIdentity.loadOrCreate(tag: peerTag)
        defer { DeviceIdentity.delete(tag: peerTag) }

        let paired = expectation(description: "peer received pair.ok")
        let peer = try TransportPeer(
            identity: peerIdentity,
            host: "127.0.0.1",
            port: port,
            expectedServerFingerprint: offer.fingerprint
        )
        peer.receive { envelope in
            if envelope.type == "pair.ok" { paired.fulfill() }
        }
        peer.start { result in
            guard case .success = result else { return }
            var payload: [String: JSONValue] = [
                "token": .string(offer.token),
                "deviceId": .string("android-int"),
                "name": .string("Galaxy S24"),
                "platform": .string("android")
            ]
            if offer.token.count == 6 {
                payload["token"] = .string(offer.token)
            }
            try? peer.send(Envelope(type: "pair.request", payload: .object(payload)))
        }

        wait(for: [paired], timeout: 15)

        // The service persists the peer asynchronously after handling the frame.
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if !service.peers.isEmpty { break }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }

        XCTAssertEqual(service.peers.map(\.name), ["Galaxy S24"])
        XCTAssertEqual(PeerStore(url: storeURL).load().map(\.fingerprint), [peerIdentity.fingerprint])

        peer.close()
    }

    private func freePort() throws -> UInt16 {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(socketFD, 0)
        defer { Darwin.close(socketFD) }

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
        return UInt16(bigEndian: address.sin_port)
    }
}
