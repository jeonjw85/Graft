import Foundation

/// A type-safe representation of an arbitrary JSON value.
public enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
}

extension JSONValue {
    public var numberValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }

    /// A non-negative whole number that fits `UInt64`.
    ///
    /// Untrusted payloads reach these conversions before any pairing check, and `UInt64(Double)`
    /// traps on negative or out-of-range values, so every wire number goes through here.
    public var uint64Value: UInt64? {
        guard let value = numberValue,
              value.isFinite,
              value >= 0,
              value < 18_446_744_073_709_551_616,
              value == value.rounded() else {
            return nil
        }
        return UInt64(value)
    }

    /// A non-negative whole number that fits `UInt32`.
    public var uint32Value: UInt32? {
        guard let value = uint64Value, value <= UInt64(UInt32.max) else { return nil }
        return UInt32(value)
    }
}

extension JSONValue: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "unsupported JSON value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let value):
            try container.encode(value)
        case .number(let value):
            try container.encode(value)
        case .string(let value):
            try container.encode(value)
        case .array(let value):
            try container.encode(value)
        case .object(let value):
            try container.encode(value)
        }
    }
}
