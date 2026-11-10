package kr.jjw.graft.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A phone or Mac this device trusts. The JSON field names are shared with macOS. */
@Serializable
data class PairedPeer(
    val fingerprint: String,
    val name: String,
    val platform: String,
    val pairedAtMillis: Long,
)

/**
 * Persists the paired peer list as JSON.
 *
 * Kept as a small file-backed store rather than DataStore so the JSON shape and the ordering rules
 * are identical to the macOS `PeerStore`, and so this class has no Android dependency and can be
 * unit tested on the JVM.
 */
class PeerStore(private val file: File) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun load(): List<PairedPeer> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) return emptyList()

        return runCatching {
            json.decodeFromString<PeerFile>(text).peers
        }.getOrElse {
            // Preserve an unreadable file instead of overwriting it, matching macOS behavior.
            val backup = File(file.path + ".corrupt")
            if (!backup.exists()) file.copyTo(backup)
            emptyList()
        }
    }

    @Synchronized
    fun save(peers: List<PairedPeer>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(PeerFile(peers)))
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    @Synchronized
    fun add(peer: PairedPeer) {
        val peers = load().toMutableList()
        val index = peers.indexOfFirst { it.fingerprint == peer.fingerprint }
        if (index >= 0) {
            peers[index] = peer
        } else {
            peers.add(peer)
        }
        save(peers)
    }

    @Synchronized
    fun remove(fingerprint: String) {
        save(load().filterNot { it.fingerprint == fingerprint })
    }

    @Serializable
    private data class PeerFile(val peers: List<PairedPeer> = emptyList())
}
