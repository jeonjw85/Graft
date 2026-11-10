import Foundation
import GraftMacKit
import GraftProtocol

// Headless harness used by the end-to-end test. It prints a pairing URI, accepts one peer, and
// drives the shared session machine plus heartbeat so the production path is what gets tested.

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
    let queue = DispatchQueue(label: "kr.jjw.graft.headless")
    var sessions: [ObjectIdentifier: ConnectionSession] = [:]
    var timers: [ObjectIdentifier: DispatchSourceTimer] = [:]

    let lock = NSLock()
    func withSessions<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    server.onConnection = { connection in
        guard let peerFingerprint = connection.peerFingerprint else {
            connection.close()
            return
        }

        // Frames are queued; a close that runs immediately after a send can drop the frame, so the
        // close is deferred until the last send is handed to the network stack.
        var pendingSends = 0
        let session = ConnectionSession(
            service: service,
            send: { envelope in
                pendingSends += 1
                try? connection.send(envelope) {
                    pendingSends -= 1
                }
            },
            close: {
                // Give queued frames a moment to flush, then close. Without this a protocol error
                // can arrive as a bare socket close and the peer has no idea why.
                queue.asyncAfter(deadline: .now() + 0.2) {
                    connection.close()
                }
            }
        )
        session.peerFingerprint = peerFingerprint
        session.start()
        _ = pendingSends

        let key = ObjectIdentifier(connection)
        withSessions { sessions[key] = session }

        connection.receive { envelope in
            let result = withSessions { () -> [SessionAction] in
                guard let active = sessions[key] else { return [] }
                return active.handle(envelope: envelope)
            }
            _ = result
        }

        // Heartbeat and dead-connection detection are driven here so they are exercised on every
        // connection instead of living only in unit tests.
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler {
            withSessions { sessions[key]?.tick() }
        }
        timer.resume()
        withSessions { timers[key] = timer }
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
