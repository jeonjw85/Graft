import CryptoKit
import Foundation
import Security

/// Computes the base64url-encoded SHA-256 fingerprint of a SubjectPublicKeyInfo block.
///
/// Graft pins peers by this value. Swift, OpenSSL (`openssl pkey -pubin -outform DER`), and
/// Java must all produce the same string for the same key, so the encoding is fixed here:
/// `30 59` + P-256 algorithm identifier + `03 42 00` (bit string) + 65-byte uncompressed point.
public enum SpkiFingerprint {
    /// Prefix for an EC P-256 SubjectPublicKeyInfo with a 65-byte uncompressed point.
    private static let p256SpkiPrefix = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    ])

    public static func base64URL(spki: Data) -> String {
        let digest = SHA256.hash(data: spki)
        return Data(digest)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Builds the SPKI DER for a certificate's P-256 public key and returns its fingerprint.
    ///
    /// `SecKeyCopyExternalRepresentation` returns the raw uncompressed point (`04 || X || Y`) for
    /// EC keys, so wrapping it in the fixed P-256 SPKI prefix yields the same bytes OpenSSL and
    /// Java produce.
    public static func base64URL(certificate: SecCertificate) throws -> String {
        guard let key = SecCertificateCopyKey(certificate) else {
            throw SpkiError.missingPublicKey
        }
        return try base64URL(publicKey: key)
    }

    public static func base64URL(publicKey: SecKey) throws -> String {
        // Structural check instead of attribute lookup: a P-256 public key is exactly the
        // 65-byte uncompressed point. Keys materialized from a certificate may not carry
        // kSecAttrKeySizeInBits, so relying on attributes would reject valid P-256 keys.
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
              raw.count == 65,
              raw.first == 0x04 else {
            throw SpkiError.unsupportedKeyType
        }
        return base64URL(spki: p256SpkiPrefix + raw)
    }
}

public enum SpkiError: Error, Equatable {
    case missingPublicKey
    case unsupportedKeyType
}
