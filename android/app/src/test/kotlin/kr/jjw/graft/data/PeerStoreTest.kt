package kr.jjw.graft.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PeerStoreTest {
    @TempDir
    lateinit var directory: Path

    private fun store(): PeerStore = PeerStore(File(directory.toFile(), "peers.json"))

    private fun peer(fingerprint: String, name: String = "MacBook Pro"): PairedPeer =
        PairedPeer(fingerprint, name, "macos", 1_762_752_000_000)

    @Test
    fun `round trip`() {
        val store = store()
        store.add(peer("fp-one"))

        assertEquals(listOf(peer("fp-one")), store().load())
    }

    @Test
    fun `add replaces the same fingerprint`() {
        val store = store()
        store.add(peer("fp-one", "Old"))
        store.add(peer("fp-one", "New"))

        assertEquals(1, store.load().size)
        assertEquals("New", store.load().single().name)
    }

    @Test
    fun `remove leaves other peers`() {
        val store = store()
        store.add(peer("fp-one"))
        store.add(peer("fp-two", "Other Mac"))

        store.remove("fp-one")

        assertEquals(listOf("fp-two"), store.load().map { it.fingerprint })
    }

    @Test
    fun `missing file loads as empty`() {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `corrupt file loads as empty and is preserved`() {
        val file = File(directory.toFile(), "peers.json")
        file.writeText("not json")

        assertTrue(PeerStore(file).load().isEmpty())
        assertTrue(File(file.path + ".corrupt").exists())
    }

    @Test
    fun `json shape matches the mac contract`() {
        val store = store()
        store.add(peer("fp-one"))

        val text = File(directory.toFile(), "peers.json").readText()

        assertTrue(text.contains("\"fingerprint\""))
        assertTrue(text.contains("\"name\""))
        assertTrue(text.contains("\"platform\""))
        assertTrue(text.contains("\"pairedAtMillis\""))
        assertTrue(text.contains("1762752000000"))
    }

    @Test
    fun `blank file loads as empty`() {
        val file = File(directory.toFile(), "peers.json")
        file.writeText("")

        assertTrue(PeerStore(file).load().isEmpty())
    }
}
