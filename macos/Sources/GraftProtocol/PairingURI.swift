import Foundation

public enum PairingURIError: Error, Equatable {
    case notAPairingURI
    case unsupportedVersion
    case invalidPort
    case invalidFingerprint
    case invalidToken
    case missingField(String)
}

public struct PairingOffer: Equatable, Sendable {
    public let version: Int
    public let host: String
    public let port: UInt16
    public let fingerprint: String
    public let token: String
    public let name: String

    public init(version: Int = 1, host: String, port: UInt16, fingerprint: String, token: String, name: String) {
        self.version = version
        self.host = host
        self.port = port
        self.fingerprint = fingerprint
        self.token = token
        self.name = name
    }
}

public enum PairingURI {
    public static let scheme = "graft"
    public static let host = "pair"

    /// Builds the exact string that goes into the QR code.
    public static func build(
        host: String,
        port: UInt16,
        fingerprint: String,
        token: String,
        name: String
    ) -> String {
        var components = URLComponents()
        components.scheme = scheme
        components.host = Self.host
        components.queryItems = [
            URLQueryItem(name: "v", value: "1"),
            URLQueryItem(name: "h", value: host),
            URLQueryItem(name: "p", value: String(port)),
            URLQueryItem(name: "fp", value: fingerprint),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "n", value: name)
        ]
        return components.string ?? ""
    }

    public static func parse(_ raw: String) throws -> PairingOffer {
        guard let components = URLComponents(string: raw),
              components.scheme == scheme,
              components.host == Self.host else {
            throw PairingURIError.notAPairingURI
        }

        let items = components.queryItems ?? []
        func value(_ name: String) throws -> String {
            guard let item = items.first(where: { $0.name == name }), let value = item.value else {
                throw PairingURIError.missingField(name)
            }
            return value
        }

        guard let version = Int(try value("v")) else {
            throw PairingURIError.unsupportedVersion
        }
        guard version == 1 else {
            throw PairingURIError.unsupportedVersion
        }

        guard let port = UInt16(try value("p")), port != 0 else {
            throw PairingURIError.invalidPort
        }

        let fingerprint = try value("fp")
        guard isValidFingerprint(fingerprint) else {
            throw PairingURIError.invalidFingerprint
        }

        let token = try value("t")
        guard isValidToken(token) else {
            throw PairingURIError.invalidToken
        }

        return PairingOffer(
            version: version,
            host: try value("h"),
            port: port,
            fingerprint: fingerprint,
            token: token,
            name: try value("n")
        )
    }

    /// A SPKI SHA-256 fingerprint is 32 bytes encoded without padding, so 43 base64url characters.
    public static func isValidFingerprint(_ value: String) -> Bool {
        value.count == 43 && value.allSatisfy { character in
            character.isASCII && (character.isLetter || character.isNumber || character == "-" || character == "_")
        }
    }

    /// Either a 16-128 character base64url token or the six-digit manual fallback code.
    public static func isValidToken(_ value: String) -> Bool {
        if value.count == 6, value.allSatisfy({ $0.isNumber }) {
            return true
        }
        guard (16...128).contains(value.count) else { return false }
        return value.allSatisfy { character in
            character.isASCII && (character.isLetter || character.isNumber || character == "-" || character == "_")
        }
    }
}
