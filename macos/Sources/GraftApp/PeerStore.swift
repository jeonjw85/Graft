import Foundation

/// Reads and writes the paired phone list.
///
/// The file is the source of truth for which fingerprints the TLS layer accepts, so writes are
/// atomic and the file is owner-readable only. The JSON shape (`{"peers": [...]}`) is shared with
/// Android.
final class PeerStore {
    private let url: URL
    private let fileManager = FileManager.default

    init(url: URL) {
        self.url = url
    }

    func load() -> [PairedPeer] {
        guard fileManager.fileExists(atPath: url.path) else { return [] }

        guard let data = try? Data(contentsOf: url), !data.isEmpty else {
            // An empty file is a legitimate "no peers" state; anything unparseable is preserved.
            if let data = try? Data(contentsOf: url), data.isEmpty { return [] }
            preserveCorruptFile()
            return []
        }

        do {
            let decoded = try JSONDecoder().decode(File.self, from: data)
            return decoded.peers
        } catch {
            preserveCorruptFile()
            return []
        }
    }

    func save(_ peers: [PairedPeer]) throws {
        let directory = url.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: directory.path) {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(File(peers: peers))

        try data.write(to: url, options: [.atomic])
        try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    func add(_ peer: PairedPeer) throws {
        var peers = load()
        if let index = peers.firstIndex(where: { $0.fingerprint == peer.fingerprint }) {
            peers[index] = peer
        } else {
            peers.append(peer)
        }
        try save(peers)
    }

    func remove(fingerprint: String) throws {
        try save(load().filter { $0.fingerprint != fingerprint })
    }

    private func preserveCorruptFile() {
        let backup = url.path + ".corrupt"
        if !fileManager.fileExists(atPath: backup) {
            try? fileManager.moveItem(at: url, to: URL(fileURLWithPath: backup))
        }
    }

    private struct File: Codable {
        let peers: [PairedPeer]
    }
}
