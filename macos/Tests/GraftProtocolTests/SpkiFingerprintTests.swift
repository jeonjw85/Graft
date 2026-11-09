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
