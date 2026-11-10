import Foundation

public enum BinaryFrameError: Error, Equatable {
    case tooShort
    case badMagic
    case unsupportedVersion
    case reservedNotZero
    case oversizedPayload
}

/// One binary file-data frame.
///
/// Layout (16-byte header, big-endian):
///
/// | offset | size | meaning |
/// |---|---|---|
/// | 0 | 2 | magic `0x47 0x46` |
/// | 2 | 1 | header version (`1`) |
/// | 3 | 1 | reserved (`0`) |
/// | 4 | 4 | transfer id (uint32) |
/// | 8 | 8 | offset within the file (uint64) |
/// | 16 | N | data (at most 64 KiB) |
public struct BinaryFrame: Equatable, Sendable {
    public static let headerSize = 16
    public static let maxDataSize = 64 * 1024
    private static let magic: (UInt8, UInt8) = (0x47, 0x46)
    private static let version: UInt8 = 1

    public let transferId: UInt32
    public let offset: UInt64
    public let data: Data

    public init(transferId: UInt32, offset: UInt64, data: Data) throws {
        guard data.count <= Self.maxDataSize else {
            throw BinaryFrameError.oversizedPayload
        }
        self.transferId = transferId
        self.offset = offset
        self.data = data
    }

    public func encoded() -> Data {
        var encoded = Data(capacity: Self.headerSize + data.count)
        encoded.append(Self.magic.0)
        encoded.append(Self.magic.1)
        encoded.append(Self.version)
        encoded.append(0)
        withUnsafeBytes(of: transferId.bigEndian) { encoded.append(contentsOf: $0) }
        withUnsafeBytes(of: offset.bigEndian) { encoded.append(contentsOf: $0) }
        encoded.append(data)
        return encoded
    }

    public static func decode(_ raw: Data) throws -> BinaryFrame {
        guard raw.count >= headerSize else { throw BinaryFrameError.tooShort }

        let bytes = [UInt8](raw.prefix(headerSize))
        guard bytes[0] == magic.0, bytes[1] == magic.1 else { throw BinaryFrameError.badMagic }
        guard bytes[2] == version else { throw BinaryFrameError.unsupportedVersion }
        guard bytes[3] == 0 else { throw BinaryFrameError.reservedNotZero }

        let transferId = bytes[4..<8].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        let offset = bytes[8..<16].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        let payload = raw.subdata(in: headerSize..<raw.count)
        guard payload.count <= maxDataSize else { throw BinaryFrameError.oversizedPayload }

        return try BinaryFrame(transferId: transferId, offset: offset, data: payload)
    }
}
