import Foundation

/// Envelope of a protocol v1 control frame.
public struct Envelope: Equatable, Sendable {
    public var v: Int
    public var id: String
    public var re: String?
    public var type: String
    public var ts: Int64
    public var payload: JSONValue

    public init(
        v: Int = MessageCodec.currentVersion,
        id: String = UUID().uuidString,
        re: String? = nil,
        type: String,
        ts: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        payload: JSONValue = .object([:])
    ) {
        self.v = v
        self.id = id
        self.re = re
        self.type = type
        self.ts = ts
        self.payload = payload
    }
}

extension Envelope: Codable {
    enum CodingKeys: String, CodingKey {
        case v, id, re, type, ts, payload
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        v = try container.decode(Int.self, forKey: .v)
        id = try container.decode(String.self, forKey: .id)
        re = try container.decodeIfPresent(String.self, forKey: .re)
        type = try container.decode(String.self, forKey: .type)
        ts = try container.decode(Int64.self, forKey: .ts)
        payload = try container.decode(JSONValue.self, forKey: .payload)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(v, forKey: .v)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(re, forKey: .re)
        try container.encode(type, forKey: .type)
        try container.encode(ts, forKey: .ts)
        try container.encode(payload, forKey: .payload)
    }
}
