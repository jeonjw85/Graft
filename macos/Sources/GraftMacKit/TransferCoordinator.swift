import Foundation
import GraftProtocol

public struct TransferProgress: Equatable, Sendable {
    public let name: String
    public let confirmed: UInt64
    public let total: UInt64
    public let direction: TransferDirection

    public init(name: String, confirmed: UInt64, total: UInt64, direction: TransferDirection) {
        self.name = name
        self.confirmed = confirmed
        self.total = total
        self.direction = direction
    }

    public var fraction: Double {
        total == 0 ? 0 : Double(confirmed) / Double(total)
    }
}

public enum TransferFailure: Error, Equatable {
    case unpairedPeer
    case duplicateTransfer
    case unsafeName
    case wrongOffset
    case hashMismatch
    case diskFull
}

/// Runs file transfers for one connection: answers offers, writes incoming data, and streams
/// outgoing files.
///
/// Auto-accept applies only when `isPaired` returns true, which is the spec's rule.
/// @unchecked Sendable: protocol state stays on the main queue; the only background work is
/// the windowed send, which touches `sendWindow`/`sendLock` and nothing shared besides that.
public final class TransferCoordinator: @unchecked Sendable {
    public enum Mode {
        case receiveOnly
        case sendOnly
        case both
    }

    private let directory: URL
    private let isPaired: () -> Bool
    private let mode: Mode
    private let sendControl: (Envelope) -> Void
    private let sendBinary: (Data) -> Void
    private let sendBinaryAsync: ((Data, @escaping () -> Void) -> Void)?
    private let records: TransferRecordStore?

    /// How many 64 KiB frames may be in the socket queue at once (1 MiB).
    private static let sendWindowSize = 16
    private let sendWindow = DispatchSemaphore(value: sendWindowSize)
    private let sendQueue = DispatchQueue(label: "kr.jjw.graft.send", qos: .utility)
    private let sendLock = NSLock()
    private var sendCancelled = false
    private var activeSends: Set<UInt32> = []

    /// Called on the main queue when send progress moves, so the UI can refresh.
    public var onProgress: ((UInt32) -> Void)?

    private var receivers: [UInt32: FileReceiver] = [:]
    private var senders: [UInt32: FileSender] = [:]
    private var offers: [UInt32: FileOffer] = [:]
    private var pendingSends: [UInt32: URL] = [:]

    public private(set) var progress: [UInt32: TransferProgress] = [:]
    public private(set) var failures: [UInt32: TransferFailure] = [:]
    public private(set) var completed: Set<UInt32> = []

    public init(
        directory: URL,
        mode: Mode = .both,
        isPaired: @escaping () -> Bool,
        sendControl: @escaping (Envelope) -> Void,
        sendBinary: @escaping (Data) -> Void,
        sendBinaryAsync: ((Data, @escaping () -> Void) -> Void)? = nil,
        records: TransferRecordStore? = nil
    ) {
        self.directory = directory
        self.mode = mode
        self.isPaired = isPaired
        self.sendControl = sendControl
        self.sendBinary = sendBinary
        self.sendBinaryAsync = sendBinaryAsync
        self.records = records
        records?.purgeExpired()
    }

    /// Registers a local file to send. Returns the transfer id used.
    @discardableResult
    public func offer(url: URL, transferId: UInt32) throws -> FileOffer {
        guard mode != .receiveOnly else { throw TransferFailure.duplicateTransfer }
        guard senders[transferId] == nil else { throw TransferFailure.duplicateTransfer }

        let sender = try FileSender(url: url)
        senders[transferId] = sender
        pendingSends[transferId] = url

        let offer = sender.offer(transferId: transferId)
        offers[transferId] = offer
        progress[transferId] = TransferProgress(
            name: offer.name,
            confirmed: 0,
            total: offer.size,
            direction: .send
        )
        sendControl(envelope(for: offer))
        return offer
    }

    public func onControl(_ envelope: Envelope) {
        switch envelope.type {
        case "file.offer":
            handleOffer(envelope)
        case "file.accept":
            handleAccept(envelope)
        case "file.reject":
            handleReject(envelope)
        case "file.complete":
            handleComplete(envelope)
        case "file.cancel":
            handleCancel(envelope)
        default:
            break
        }
    }

    public func onBinary(_ data: Data) {
        guard let frame = try? BinaryFrame.decode(data) else { return }
        guard let receiver = receivers[frame.transferId] else { return }

        do {
            try receiver.write(frame)
            recordProgress(frame.transferId, receiver)
            progress[frame.transferId] = TransferProgress(
                name: progress[frame.transferId]?.name ?? "file",
                confirmed: receiver.confirmedBytes,
                total: offers[frame.transferId]?.size ?? receiver.confirmedBytes,
                direction: .receive
            )
        } catch {
            let full = (error as NSError).code == NSFileWriteOutOfSpaceError
            failures[frame.transferId] = full ? .diskFull : .wrongOffset
            try? receiver.cancel()
            receivers.removeValue(forKey: frame.transferId)
            sendControl(
                errorEnvelope(
                    code: full ? "disk_full" : "bad_payload",
                    message: full ? "not enough space" : "chunk at the wrong offset"
                )
            )
        }
    }

    // MARK: - Inbound

    private func handleOffer(_ envelope: Envelope) {
        guard mode != .sendOnly else { return }
        guard isPaired() else {
            sendControl(errorEnvelope(code: "unpaired", message: "pairing required"))
            return
        }

        guard let payload = envelope.payload.objectValue,
              let tid = payload["tid"]?.uint32Value,
              let name = payload["name"]?.stringValue,
              let size = payload["size"]?.uint64Value else {
            sendControl(errorEnvelope(code: "bad_payload", message: "file.offer fields missing"))
            return
        }

        guard receivers[tid] == nil else {
            sendControl(errorEnvelope(code: "transfer_not_found", message: "transfer id already active"))
            return
        }

        let offer = FileOffer(transferId: tid, name: name, size: size, mime: "application/octet-stream")
        offers[tid] = offer

        do {
            let receiver = FileReceiver(directory: directory)
            // Resume only when a stored record agrees with what is on disk; a leftover file from an
            // unrelated transfer must not be appended to.
            let onDisk = ResumeLookup.offset(in: directory, name: offer.name, size: offer.size)
            let stored = records?.find(
                name: offer.name,
                directory: directory.path,
                size: offer.size,
                partialBytesOnDisk: onDisk
            )
            let offset = try receiver.begin(offer, resumeOffset: stored?.bytesConfirmed ?? 0)
            receivers[tid] = receiver
            progress[tid] = TransferProgress(
                name: name,
                confirmed: offset,
                total: size,
                direction: .receive
            )
            sendControl(
                controlEnvelope(type: "file.accept", payload: [
                    "tid": .number(Double(tid)),
                    "offset": .number(Double(offset))
                ])
            )
        } catch {
            failures[tid] = .unsafeName
            sendControl(errorEnvelope(code: "bad_payload", message: "unsafe file name"))
        }
    }

    private func handleAccept(_ envelope: Envelope) {
        guard let payload = envelope.payload.objectValue,
              let tid = payload["tid"]?.uint32Value,
              let offset = payload["offset"]?.uint64Value,
              let sender = senders[tid] else {
            return
        }

        // The windowed path keeps a large file off the main thread and out of memory. Tests and the
        // headless harness pass no async sender and take the synchronous path below.
        if sendBinaryAsync != nil {
            startWindowedSend(tid: tid, offset: offset, sender: sender)
            return
        }

        do {
            for frame in try sender.frames(transferId: tid, from: offset) {
                sendBinary(frame.encoded())
                progress[tid] = TransferProgress(
                    name: sender.name,
                    confirmed: frame.offset + UInt64(frame.data.count),
                    total: sender.size,
                    direction: .send
                )
            }
            sendControl(
                controlEnvelope(type: "file.complete", payload: [
                    "tid": .number(Double(tid)),
                    "sha256": .string(try sender.sha256())
                ])
            )
        } catch {
            sendControl(errorEnvelope(code: "bad_payload", message: "could not read the file"))
        }
    }

    private func startWindowedSend(tid: UInt32, offset: UInt64, sender: FileSender) {
        sendLock.lock()
        if activeSends.contains(tid) {
            sendLock.unlock()
            return
        }
        activeSends.insert(tid)
        sendCancelled = false
        sendLock.unlock()

        sendQueue.async { [weak self] in
            guard let self else { return }
            do {
                var published: UInt64 = offset
                try sender.forEachChunk(transferId: tid, from: offset) { frame in
                    try self.awaitWindow()
                    let data = frame.encoded()
                    self.sendBinaryAsync?(data) { self.sendWindow.signal() }
                    let confirmed = frame.offset + UInt64(frame.data.count)
                    if confirmed - published >= 512 * 1024 || confirmed == sender.size {
                        published = confirmed
                        DispatchQueue.main.async {
                            self.progress[tid] = TransferProgress(
                                name: sender.name,
                                confirmed: confirmed,
                                total: sender.size,
                                direction: .send
                            )
                            self.onProgress?(tid)
                        }
                    }
                }
                self.drainWindow()
                let digest = try sender.sha256()
                DispatchQueue.main.async {
                    self.finishSend(tid: tid, sender: sender, digest: digest)
                }
            } catch {
                DispatchQueue.main.async {
                    self.failSend(tid: tid)
                }
            }
        }
    }

    private func finishSend(tid: UInt32, sender: FileSender, digest: String) {
        progress[tid] = TransferProgress(
            name: sender.name,
            confirmed: sender.size,
            total: sender.size,
            direction: .send
        )
        completed.insert(tid)
        senders.removeValue(forKey: tid)
        sendControl(
            controlEnvelope(type: "file.complete", payload: [
                "tid": .number(Double(tid)),
                "sha256": .string(digest)
            ])
        )
        sendLock.lock()
        activeSends.remove(tid)
        sendLock.unlock()
        onProgress?(tid)
    }

    private func failSend(tid: UInt32) {
        failures[tid] = .wrongOffset
        senders.removeValue(forKey: tid)
        sendLock.lock()
        activeSends.remove(tid)
        sendLock.unlock()
        sendControl(errorEnvelope(code: "bad_payload", message: "could not read the file"))
        onProgress?(tid)
    }

    /// Waits for room in the socket queue, but gives up if the connection went away.
    private func awaitWindow() throws {
        while sendWindow.wait(timeout: .now() + 0.5) == .timedOut {
            sendLock.lock()
            let stopped = sendCancelled
            sendLock.unlock()
            if stopped {
                throw TransportFailure.cancelled
            }
        }
    }

    /// Waits until every queued frame has been processed, so `file.complete` cannot overtake data.
    private func drainWindow() {
        var taken = 0
        while taken < Self.sendWindowSize {
            guard sendWindow.wait(timeout: .now() + 15) == .success else { break }
            taken += 1
        }
        for _ in 0..<taken {
            sendWindow.signal()
        }
    }

    /// Stops every send when the connection dies; the background loop exits on the next frame.
    public func cancelAll() {
        sendLock.lock()
        sendCancelled = true
        activeSends.removeAll()
        sendLock.unlock()
    }

    private enum TransportFailure: Error {
        case cancelled
    }

    private func handleReject(_ envelope: Envelope) {
        guard let tid = tid(from: envelope) else { return }
        failures[tid] = .unpairedPeer
        senders.removeValue(forKey: tid)
    }

    private func handleComplete(_ envelope: Envelope) {
        guard let payload = envelope.payload.objectValue,
              let tid = payload["tid"]?.uint32Value,
              let expected = payload["sha256"]?.stringValue,
              let receiver = receivers[tid] else {
            return
        }

        do {
            let url = try receiver.finish(expectedSha256: expected)
            completed.insert(tid)
            offers[tid].map { records?.remove(name: $0.name, directory: directory.path) }
            progress[tid] = TransferProgress(
                name: url.lastPathComponent,
                confirmed: offers[tid]?.size ?? 0,
                total: offers[tid]?.size ?? 0,
                direction: .receive
            )
            receivers.removeValue(forKey: tid)
        } catch FileReceiverError.hashMismatch {
            failures[tid] = .hashMismatch
            // A corrupt partial must not be resumed onto.
            offers[tid].map { records?.remove(name: $0.name, directory: directory.path) }
            receivers.removeValue(forKey: tid)
            sendControl(errorEnvelope(code: "hash_mismatch", message: "hash mismatch"))
        } catch {
            failures[tid] = .wrongOffset
            receivers.removeValue(forKey: tid)
        }
    }

    private func handleCancel(_ envelope: Envelope) {
        guard let tid = tid(from: envelope) else { return }
        receivers[tid]?.cancel()
        receivers.removeValue(forKey: tid)
        senders.removeValue(forKey: tid)
    }

    // MARK: - Helpers

    private func tid(from envelope: Envelope) -> UInt32? {
        envelope.payload.objectValue?["tid"]?.uint32Value
    }

    private func recordProgress(_ transferId: UInt32, _ receiver: FileReceiver) {
        guard let records, let offer = offers[transferId] else { return }
        records.upsert(
            ResumeRecord(
                name: offer.name,
                directory: directory.path,
                size: offer.size,
                bytesConfirmed: receiver.confirmedBytes,
                updatedAtMillis: Int64(Date().timeIntervalSince1970 * 1000)
            )
        )
    }

    private func envelope(for offer: FileOffer) -> Envelope {
        controlEnvelope(type: "file.offer", payload: [
            "tid": .number(Double(offer.transferId)),
            "name": .string(offer.name),
            "size": .number(Double(offer.size)),
            "mime": .string(offer.mime)
        ])
    }

    private func controlEnvelope(type: String, payload: [String: JSONValue]) -> Envelope {
        Envelope(type: type, payload: .object(payload))
    }

    private func errorEnvelope(code: String, message: String) -> Envelope {
        Envelope(type: "error", payload: .object([
            "code": .string(code),
            "message": .string(message)
        ]))
    }
}

