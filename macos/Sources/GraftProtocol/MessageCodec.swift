import Foundation

public enum CodecError: Error, Equatable, Sendable {
    case unsupportedVersion(Int)
    case oversized(Int)
    case malformed(String)
}

/// 프로토콜 v1 제어 프레임의 인코딩·디코딩.
public enum MessageCodec {
    public static let currentVersion = 1
    public static let maxControlFrameBytes = 512 * 1024

    public static func encode(_ envelope: Envelope) throws -> Data {
        let data = try JSONEncoder().encode(envelope)
        guard data.count <= maxControlFrameBytes else {
            throw CodecError.oversized(data.count)
        }
        return data
    }

    public static func decode(_ data: Data) throws -> Envelope {
        guard data.count <= maxControlFrameBytes else {
            throw CodecError.oversized(data.count)
        }

        let envelope: Envelope
        do {
            envelope = try JSONDecoder().decode(Envelope.self, from: data)
        } catch {
            throw CodecError.malformed("invalid utf8 or json")
        }

        guard envelope.v == currentVersion else {
            throw CodecError.unsupportedVersion(envelope.v)
        }
        return envelope
    }
}
