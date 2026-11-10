import Foundation

/// A phone the Mac trusts. The JSON shape is shared with Android; `pairedAt` is epoch
/// milliseconds so both platforms write byte-compatible files.
struct PairedPeer: Codable, Equatable {
    let fingerprint: String
    let name: String
    let platform: String
    let pairedAtMillis: Int64
}
