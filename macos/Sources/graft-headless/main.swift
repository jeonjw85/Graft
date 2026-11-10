import Foundation
import GraftMacKit
import GraftProtocol

// Headless harness used by the end-to-end test. It prints a pairing URI, accepts one peer, and
// applies the protocol rules from PairingService so a real client can exercise pairing over TLS.

struct Arguments {
    var port: UInt16 = 47800
    var identityTag = "kr.jjw.graft.headless"
    var deviceName = "Graft Headless"
    var host = "127.0.0.1"
}

func parseArguments() -> Arguments {
    var arguments = Arguments()
    var iterator = CommandLine.arguments.dropFirst().makeIterator()
    while let argument = iterator.next() {
        switch argument {
        case "--port":
            if let value = iterator.next(), let port = UInt16(value) { arguments.port = port }
        case "--identity-tag":
            if let value = iterator.next() { arguments.identityTag = value }
        case "--name":
            if let value = iterator.next() { arguments.deviceName = value }
        case "--host":
            if let value = iterator.next() { arguments.host = value }
        default:
            continue
        }
    }
    return arguments
}

let arguments = parseArguments()
setvbuf(stdout, nil, _IONBF, 0)

do {
    let identity = try DeviceIdentity.loadOrCreate(tag: arguments.identityTag)
    let service = PairingService(local: identity)
    let authorizer = PeerAuthorizer(
        allowed: { service.allowedFingerprints },
        isPairingOpen: { service.isPairingOpen() }
    )

    let server = TransportServer(identity: identity, authorizer: authorizer, port: arguments.port)
    server.onConnection = { connection in
        connection.receive { envelope in
            // Fail closed: without an authenticated fingerprint we cannot tell who this is.
            // Fail closed: without an authenticated fingerprint we cannot tell who this is.
            guard let peerFingerprint = connection.peerFingerprint else {
                connection.close()
                return
            }
            let outcome = service.handle(envelope: envelope, peerFingerprint: peerFingerprint, now: Date())
            // Send the final reply first, then close: closing immediately drops the frame and the
            // peer would see only a bare socket close.
            for reply in outcome.replies.dropLast() {
                try? connection.send(reply)
            }
            if let last = outcome.replies.last {
                try? connection.send(last) {
                    if outcome.close { connection.close() }
                }
            } else if outcome.close {
                connection.close()
            }
        }
    }
    try server.start()

    let pairing = service.beginPairing(
        now: Date(),
        host: arguments.host,
        port: try { () throws -> UInt16 in
            guard let port = server.port else { throw TransportError.listenerFailed }
            return port
        }(),
        deviceName: arguments.deviceName
    )
    print("PAIRING_URI \(pairing.uri)")
    print("FINGERPRINT \(identity.fingerprint)")

    // Stay alive until the caller terminates us. Reading stdin is not an option: when the test
    // runs us with stdin closed, readLine() returns immediately and the server exits at startup.
    signal(SIGTERM, SIG_DFL)
    signal(SIGINT, SIG_DFL)
    dispatchMain()
} catch {
    FileHandle.standardError.write(Data("headless failed: \(error)\n".utf8))
    exit(1)
}
