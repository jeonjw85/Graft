import Foundation
import GraftProtocol
import Security

public enum IdentityError: Error {
    case keychain(OSStatus)
    case certificateParse
    case identityLookup
}

/// A self-signed P-256 identity used for TLS on both sides of the connection.
///
/// The private key lives in the login keychain as a permanent item so the same identity survives
/// launches. The certificate is generated in process and stored alongside it. Nothing here
/// touches the system trust store; peers pin the fingerprint instead.
public final class DeviceIdentity {
    public let certificate: SecCertificate
    public let identity: SecIdentity
    public let fingerprint: String

    private init(certificate: SecCertificate, identity: SecIdentity) throws {
        self.certificate = certificate
        self.identity = identity
        self.fingerprint = try SpkiFingerprint.base64URL(certificate: certificate)
    }

    public static func loadOrCreate(tag: String) throws -> DeviceIdentity {
        let keychain = try defaultKeychain()
        let tagData = Data(tag.utf8)

        let privateKey: SecKey
        if let existing = try findKey(tag: tagData, keychain: keychain) {
            privateKey = existing
        } else {
            // Clear a stale public item under the same tag before generating a fresh pair.
            deleteAllKeys(tag: tagData, keychain: keychain)
            privateKey = try createKey(tag: tagData, keychain: keychain)
        }

        let certificate: SecCertificate
        if let existing = try findCertificate(matching: privateKey, keychain: keychain) {
            certificate = existing
        } else {
            certificate = try createCertificate(for: privateKey, tag: tagData)
            try store(certificate: certificate, keychain: keychain)
        }

        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(keychain, certificate, &identity)
        guard status == errSecSuccess, let identity else {
            throw IdentityError.identityLookup
        }
        return try DeviceIdentity(certificate: certificate, identity: identity)
    }

    public static func delete(tag: String) {
        let tagData = Data(tag.utf8)
        let keychain = try? defaultKeychain()

        // Remove the certificate that matches this key before removing the key itself.
        if let keychain,
           let key = try? findKey(tag: tagData, keychain: keychain),
           let certificate = try? findCertificate(matching: key, keychain: keychain) {
            SecItemDelete([
                kSecClass as String: kSecClassCertificate,
                kSecValueRef as String: certificate,
                kSecUseKeychain as String: keychain
            ] as CFDictionary)
        }

        SecItemDelete([
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tagData,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate
        ] as CFDictionary)
        deleteAllKeys(tag: tagData, keychain: keychain)
    }

    // MARK: - Keychain

    private static func defaultKeychain() throws -> SecKeychain {
        var keychain: SecKeychain?
        let status = SecKeychainCopyDefault(&keychain)
        guard status == errSecSuccess, let keychain else {
            throw IdentityError.keychain(status)
        }
        return keychain
    }

    private static func findKey(tag: Data, keychain: SecKeychain) throws -> SecKey? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecMatchSearchList as String: [keychain] as CFArray,
            kSecReturnRef as String: true
        ] as CFDictionary, &result)

        switch status {
        case errSecSuccess:
            let key = (result as! SecKey)
            // A permanent EC key pair also stores a public item under the same tag. Only a
            // private key that can sign is usable here; anything else must be replaced.
            guard SecKeyIsAlgorithmSupported(key, .sign, .ecdsaSignatureMessageX962SHA256) else {
                return nil
            }
            return key
        case errSecItemNotFound:
            return nil
        default:
            throw IdentityError.keychain(status)
        }
    }

    /// Removes every key item carrying the tag, including a public half left behind by an
    /// earlier failed creation.
    private static func deleteAllKeys(tag: Data, keychain: SecKeychain?) {
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag
        ]
        if let keychain {
            // Without an explicit search list the default keychain list is not consulted in
            // every process context, and the delete silently matches nothing.
            query[kSecMatchSearchList as String] = [keychain] as CFArray
        }
        SecItemDelete(query as CFDictionary)
    }

    private static func findCertificate(
        matching privateKey: SecKey,
        keychain: SecKeychain
    ) throws -> SecCertificate? {
        // Certificates do not round-trip an application tag reliably, so match by public key
        // instead: the certificate whose SPKI point equals our key's public point is ours.
        let publicKey = try publicKey(for: privateKey)
        guard let ourPoint = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw SpkiError.missingPublicKey
        }

        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassCertificate,
            kSecMatchSearchList as String: [keychain] as CFArray,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ] as CFDictionary, &result)

        switch status {
        case errSecSuccess:
            let certificates = result as? [SecCertificate] ?? []
            for certificate in certificates {
                guard let candidate = SecCertificateCopyKey(certificate),
                      let point = SecKeyCopyExternalRepresentation(candidate, nil) as Data? else {
                    continue
                }
                if point == ourPoint {
                    return certificate
                }
            }
            return nil
        case errSecItemNotFound:
            return nil
        default:
            throw IdentityError.keychain(status)
        }
    }

    private static func createKey(tag: Data, keychain: SecKeychain) throws -> SecKey {
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag,
            kSecUseKeychain as String: keychain
        ] as CFDictionary, &error) else {
            throw IdentityError.keychain(errSecInternalError)
        }
        return key
    }

    private static func store(certificate: SecCertificate, keychain: SecKeychain) throws {
        let status = SecItemAdd([
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecUseKeychain as String: keychain
        ] as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw IdentityError.keychain(status)
        }
    }

    // MARK: - Certificate generation

    private static func createCertificate(for key: SecKey, tag: Data) throws -> SecCertificate {
        let publicKey = try publicKey(for: key)
        guard let rawPoint = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
              rawPoint.count == 65,
              rawPoint.first == 0x04 else {
            throw SpkiError.unsupportedKeyType
        }

        let spki = DER.p256Spki(point: rawPoint)
        let signatureAlgorithm = DER.sequence(DER.oid("1.2.840.10045.4.3.2"))
        let name = DER.sequence(DER.set(DER.sequence(DER.oid("2.5.4.3"), DER.utf8("Graft Device"))))

        let notBefore = Date().addingTimeInterval(-60)
        let notAfter = Date().addingTimeInterval(60 * 60 * 24 * 3650)
        let validity = DER.sequence(DER.utcTime(notBefore), DER.utcTime(notAfter))

        let basicConstraints = DER.sequence(
            DER.oid("2.5.29.19"),
            DER.boolean(true),
            DER.octetString(DER.sequence())
        )
        let keyUsage = DER.sequence(
            DER.oid("2.5.29.15"),
            DER.boolean(true),
            DER.octetString(DER.bitString([0x80]))
        )
        let extendedKeyUsage = DER.sequence(
            DER.oid("2.5.29.37"),
            DER.octetString(DER.sequence(
                DER.oid("1.3.6.1.5.5.7.3.1"),
                DER.oid("1.3.6.1.5.5.7.3.2")
            ))
        )
        let extensions = DER.explicit(3, DER.sequence(basicConstraints, keyUsage, extendedKeyUsage))

        let serial = DER.integer(bytes: [UInt8](tag.prefix(16)) + [0x01])
        let tbs = DER.sequence(
            DER.explicit(0, DER.integer(2)),
            serial,
            signatureAlgorithm,
            name,
            validity,
            name,
            spki,
            extensions
        )

        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            key,
            .ecdsaSignatureMessageX962SHA256,
            Data(tbs) as CFData,
            &error
        ) as Data? else {
            throw IdentityError.certificateParse
        }

        let certificateDER = DER.sequence(
            tbs,
            signatureAlgorithm,
            DER.bitString([UInt8](signature))
        )

        guard let certificate = SecCertificateCreateWithData(nil, Data(certificateDER) as CFData) else {
            throw IdentityError.certificateParse
        }
        return certificate
    }

    private static func publicKey(for key: SecKey) throws -> SecKey {
        guard let publicKey = SecKeyCopyPublicKey(key) else {
            throw SpkiError.missingPublicKey
        }
        return publicKey
    }
}

/// Minimal DER writer for the certificate fields Graft needs.
enum DER {
    static func length(_ value: Int) -> [UInt8] {
        if value < 0x80 { return [UInt8(value)] }
        var remainder = value
        var bytes: [UInt8] = []
        while remainder > 0 {
            bytes.insert(UInt8(remainder & 0xFF), at: 0)
            remainder >>= 8
        }
        return [0x80 | UInt8(bytes.count)] + bytes
    }

    static func tlv(_ tag: UInt8, _ body: [UInt8]) -> [UInt8] {
        [tag] + length(body.count) + body
    }

    static func sequence(_ items: [UInt8]...) -> [UInt8] {
        tlv(0x30, items.flatMap { $0 })
    }

    static func set(_ items: [UInt8]...) -> [UInt8] {
        tlv(0x31, items.flatMap { $0 })
    }

    static func integer(_ value: Int) -> [UInt8] {
        var remainder = value
        var bytes: [UInt8] = []
        while remainder > 0 {
            bytes.insert(UInt8(remainder & 0xFF), at: 0)
            remainder >>= 8
        }
        if bytes.isEmpty { bytes = [0] }
        return integer(bytes: bytes)
    }

    static func integer(bytes: [UInt8]) -> [UInt8] {
        var body = bytes
        while body.count > 1 && body.first == 0 && body[1] < 0x80 {
            body.removeFirst()
        }
        if let first = body.first, first >= 0x80 {
            body.insert(0, at: 0)
        }
        return tlv(0x02, body)
    }

    static func oid(_ dotted: String) -> [UInt8] {
        let parts = dotted.split(separator: ".").compactMap { Int($0) }
        guard parts.count >= 2 else { return tlv(0x06, []) }
        var body: [UInt8] = [UInt8(parts[0] * 40 + parts[1])]
        for part in parts.dropFirst(2) {
            var remainder = part
            var encoded: [UInt8] = [UInt8(remainder & 0x7F)]
            remainder >>= 7
            while remainder > 0 {
                encoded.insert(UInt8((remainder & 0x7F) | 0x80), at: 0)
                remainder >>= 7
            }
            body.append(contentsOf: encoded)
        }
        return tlv(0x06, body)
    }

    static func utf8(_ value: String) -> [UInt8] {
        tlv(0x0C, Array(value.utf8))
    }

    static func boolean(_ value: Bool) -> [UInt8] {
        tlv(0x01, [value ? 0xFF : 0x00])
    }

    static func octetString(_ value: [UInt8]) -> [UInt8] {
        tlv(0x04, value)
    }

    static func bitString(_ value: [UInt8]) -> [UInt8] {
        tlv(0x03, [0x00] + value)
    }

    static func utcTime(_ date: Date) -> [UInt8] {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return tlv(0x17, Array(formatter.string(from: date).utf8))
    }

    static func explicit(_ number: UInt8, _ body: [UInt8]) -> [UInt8] {
        tlv(0xA0 | number, body)
    }

    static func p256Spki(point: Data) -> [UInt8] {
        let algorithm = sequence(oid("1.2.840.10045.2.1"), oid("1.2.840.10045.3.1.7"))
        return sequence(algorithm, bitString([UInt8](point)))
    }
}
