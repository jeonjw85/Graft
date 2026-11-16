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
    var sendPath: String?
    var receiveDir: String?
    var numeric = false
    var clipboardText: String?
    var deleteIdentity = false
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
        case "--send":
            if let value = iterator.next() { arguments.sendPath = value }
        case "--receive-dir":
            if let value = iterator.next() { arguments.receiveDir = value }
        case "--numeric":
            arguments.numeric = true
        case "--clipboard":
            if let value = iterator.next() { arguments.clipboardText = value }
        case "--delete-identity":
            arguments.deleteIdentity = true
        default:
            continue
        }
    }
    return arguments
}

let arguments = parseArguments()
setvbuf(stdout, nil, _IONBF, 0)

do {
    if arguments.deleteIdentity {
        // Keychain ACLs are tied to the binary's signature; a rebuilt ad-hoc binary cannot read a
        // key an earlier build created, so device checks need a way to start over.
        DeviceIdentity.delete(tag: arguments.identityTag)
        print("IDENTITY_DELETED \(arguments.identityTag)")
        exit(0)
    }
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
        var sentClipboard = false
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

        let receiveDirectory = URL(
            fileURLWithPath: arguments.receiveDir ?? NSTemporaryDirectory() + "graft-received-${UUID().uuidString}"
        )
        var transferId: UInt32 = 1
        let coordinator = TransferCoordinator(
            directory: receiveDirectory,
            isPaired: { true },
            sendControl: { envelope in try? connection.send(envelope) },
            sendBinary: { data in try? connection.sendBinary(data) }
        )

        connection.receiveBinary { data in
            coordinator.onBinary(data)
        }

        connection.receive { envelope in
            let result = withSessions { () -> [SessionAction] in
                guard let active = sessions[key] else { return [] }
                return active.handle(envelope: envelope)
            }
            _ = result

            coordinator.onControl(envelope)


            if envelope.type == "clipboard.update" {
                let payload = envelope.payload.objectValue ?? [:]
                print("CLIPBOARD \(payload["body"]?.stringValue ?? "")")
            }

            if envelope.type == "notification.posted" {
                // Device checks read this line to prove the phone forwarded an alert.
                let payload = envelope.payload.objectValue ?? [:]
                print(
                    "NOTIFICATION app=\(payload["app"]?.stringValue ?? "?") " +
                    "title=\(payload["title"]?.stringValue ?? "") " +
                    "text=\(payload["text"]?.stringValue ?? "")"
                )
            }

            // Auto-offer the requested file once the peer is ready, so the e2e test does not need
            // a human to press a button.
            if envelope.type == "hello.ok" || envelope.type == "pair.ok" || envelope.type == "hello" {
                // One clipboard message per connection is enough for a device check.
                if let text = arguments.clipboardText, !sentClipboard {
                    sentClipboard = true
                    try? connection.send(
                        Envelope(
                            type: "clipboard.update",
                            payload: .object([
                                "kind": .string("text"),
                                "body": .string(text),
                                "ts": .number(1)
                            ])
                        )
                    )

                }
                if let path = arguments.sendPath, coordinator.progress.isEmpty {
                    try? coordinator.offer(url: URL(fileURLWithPath: path), transferId: transferId)
                    transferId += 1
                }
            }

            if let completed = coordinator.completed.first,
               coordinator.progress[completed]?.fraction == 1.0 {
                print("TRANSFER_COMPLETE \(receiveDirectory.path)")
            }
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

    let assignedPort = try { () throws -> UInt16 in
        guard let port = server.port else { throw TransportError.listenerFailed }
        return port
    }()

    if arguments.numeric {
        // Prints what the Mac's manual pairing window shows, for device tests without a camera.
        let manual = service.beginNumericPairing(
            now: Date(),
            host: arguments.host,
            port: assignedPort,
            deviceName: arguments.deviceName
        )
        print("CODE \(manual.code)")
        print("PREFIX \(manual.fingerprintPrefix)")
        print("ADDRESS \(manual.host):\(manual.port)")
    } else {
        let pairing = service.beginPairing(
            now: Date(),
            host: arguments.host,
            port: assignedPort,
            deviceName: arguments.deviceName
        )
        print("PAIRING_URI \(pairing.uri)")
    }
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
