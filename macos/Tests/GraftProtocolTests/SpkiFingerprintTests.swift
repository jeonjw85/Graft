import Foundation
import Security
import XCTest
@testable import GraftProtocol

final class SpkiFingerprintTests: XCTestCase {
    func testKnownVector() {
        let hex = "3059301306072a8648ce3d020106082a8648ce3d0301070342000479ab6c54f4277cfdba509411f14a5eb278c2a0b06c9de1b45a57ca953e5fa376d30a9834b6801eb4179927d610ae4ac83a8806e509627c852a2d9edf1cde7b05"
        let data = Data(hexString: hex)
        XCTAssertEqual(
            SpkiFingerprint.base64URL(spki: data),
            "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"
        )
    }

    func testEmptyInputIsStillHashed() {
        XCTAssertEqual(
            SpkiFingerprint.base64URL(spki: Data()),
            "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"
        )
    }

    func testBase64URLHasNoPaddingOrUnsafeCharacters() {
        let value = SpkiFingerprint.base64URL(spki: Data(repeating: 0xFF, count: 91))
        XCTAssertFalse(value.contains("="))
        XCTAssertFalse(value.contains("+"))
        XCTAssertFalse(value.contains("/"))
        XCTAssertEqual(value.count, 43)
    }

    func testSharedIdentityVectors() throws {
        let vectors = try IdentityVectors.load()
        XCTAssertEqual(vectors.count, 2, "both languages must read the same vectors")

        for vector in vectors {
            XCTAssertEqual(
                SpkiFingerprint.base64URL(spki: vector.spki),
                vector.fingerprint,
                "vector \(vector.name)"
            )
        }
    }

    func testRawPointConstructsTheSameSpkiAsOpenSSL() throws {
        // The fixture stores the full SPKI and the raw point. Building the SPKI from the point is
        // exactly what base64URL(certificate:) does, so this pins the construction step that the
        // hash-only test above cannot cover.
        let vector = try XCTUnwrap(try IdentityVectors.load().first)

        XCTAssertEqual(vector.spki.count, 91)
        XCTAssertEqual(vector.rawPoint.count, 65)
        XCTAssertEqual(vector.spki.suffix(65), vector.rawPoint)
        XCTAssertEqual(
            try SpkiFingerprint.base64URL(publicKeyPoint: vector.rawPoint),
            vector.fingerprint
        )
    }

    func testRejectsPointWithWrongLength() {
        XCTAssertThrowsError(
            try SpkiFingerprint.base64URL(publicKeyPoint: Data(repeating: 0x04, count: 64))
        ) {
            XCTAssertEqual($0 as? SpkiError, .unsupportedKeyType)
        }
    }
}

enum IdentityVectors {
    struct Vector {
        let name: String
        let spki: Data
        let rawPoint: Data
        let fingerprint: String
    }

    static func load() throws -> [Vector] {
        let url = FixtureLoader.directory
            .appendingPathComponent("identity/expected.json")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        guard let root = object as? [String: Any], let rows = root["vectors"] as? [[String: Any]] else {
            throw NSError(domain: "IdentityVectors", code: 1)
        }
        return try rows.map { row in
            guard let name = row["name"] as? String,
                  let spkiHex = row["spkiHex"] as? String,
                  let pointHex = row["publicKeyHex"] as? String,
                  let fingerprint = row["sha256Base64Url"] as? String else {
                throw NSError(domain: "IdentityVectors", code: 2)
            }
            return Vector(
                name: name,
                spki: Data(hexString: spkiHex),
                rawPoint: Data(hexString: pointHex),
                fingerprint: fingerprint
            )
        }
    }
}

extension Data {
    init(hexString: String) {
        var bytes: [UInt8] = []
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            bytes.append(UInt8(hexString[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }
}
