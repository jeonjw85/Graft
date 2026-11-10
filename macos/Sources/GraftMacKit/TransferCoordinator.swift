import Foundation
import GraftProtocol

public struct TransferProgress: Equatable, Sendable {
    public let name: String
    public let confirmed: UInt64
    public let total: UInt64
    public let direction: TransferDirection

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
}

/// Runs file transfers for one connection: answers offers, writes incoming data, and streams
/// outgoing files.
///
/// Auto-accept applies only when `isPaired` returns true, which is the spec's rule.
public final class TransferCoordinator {
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
        sendBinary: @escaping (Data) -> Void
    ) {
        self.directory = directory
        self.mode = mode
        self.isPaired = isPaired
        self.sendControl = sendControl
        self.sendBinary = sendBinary
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
            progress[frame.transferId] = TransferProgress(
                name: progress[frame.transferId]?.name ?? "file",
                confirmed: receiver.confirmedBytes,
                total: offers[frame.transferId]?.size ?? receiver.confirmedBytes,
                direction: .receive
            )
        } catch {
            failures[frame.transferId] = .wrongOffset
            receivers.removeValue(forKey: frame.transferId)
            sendControl(errorEnvelope(code: "bad_payload", message: "chunk at the wrong offset"))
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
              let tid = payload["tid"]?.numberValue.map(UInt32.init),
              let name = payload["name"]?.stringValue,
              let size = payload["size"]?.numberValue.map(UInt64.init) else {
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
            let offset = try receiver.begin(offer, resumeOffset: resumeOffset(for: tid))
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
              let tid = payload["tid"]?.numberValue.map(UInt32.init),
              let offset = payload["offset"]?.numberValue.map(UInt64.init),
              let sender = senders[tid] else {
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

    private func handleReject(_ envelope: Envelope) {
        guard let tid = tid(from: envelope) else { return }
        failures[tid] = .unpairedPeer
        senders.removeValue(forKey: tid)
    }

    private func handleComplete(_ envelope: Envelope) {
        guard let payload = envelope.payload.objectValue,
              let tid = payload["tid"]?.numberValue.map(UInt32.init),
              let expected = payload["sha256"]?.stringValue,
              let receiver = receivers[tid] else {
            return
        }

        do {
            let url = try receiver.finish(expectedSha256: expected)
            completed.insert(tid)
            progress[tid] = TransferProgress(
                name: url.lastPathComponent,
                confirmed: offers[tid]?.size ?? 0,
                total: offers[tid]?.size ?? 0,
                direction: .receive
            )
            receivers.removeValue(forKey: tid)
        } catch FileReceiverError.hashMismatch {
            failures[tid] = .hashMismatch
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
        envelope.payload.objectValue?["tid"]?.numberValue.map(UInt32.init)
    }

    private func resumeOffset(for transferId: UInt32) -> UInt64 {
        // v0.1 resumes only within one session; a restart starts from zero until the record store
        // lands, which the plan defers to the app layer.
        0
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

extension JSONValue {
    /// Numeric payload fields arrive as JSON numbers; ranges are validated by the caller.
    var numberValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }
}
