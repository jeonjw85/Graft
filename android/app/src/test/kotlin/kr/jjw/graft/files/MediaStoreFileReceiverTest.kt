package kr.jjw.graft.files

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * The real MediaStore insert needs a device, so the receiver depends on [MediaStorePort]. This fake
 * records the pending flag transitions the device path relies on.
 */
class MediaStoreFileReceiverTest {
    private class FakePort : MediaStorePort {
        val sink = ByteArrayOutputStream()
        var insertedName: String? = null
        var insertedMime: String? = null
        var insertedPath: String? = null
        var pending = false
        var deleted = false
        var cleared = false

        override fun insertPending(displayName: String, mime: String, relativePath: String): Any? {
            insertedName = displayName
            insertedMime = mime
            insertedPath = relativePath
            pending = true
            return "row-1"
        }

        override fun openOutput(handle: Any) = sink

        override fun clearPending(handle: Any) {
            pending = false
            cleared = true
        }

        override fun delete(handle: Any) {
            deleted = true
            pending = false
        }
    }

    private fun offer(size: Int) =
        FileOffer(transferId = 1u, name = "보고서.pdf", size = size.toULong(), mime = "application/pdf")

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun payload(count: Int) = ByteArray(count) { index -> (index % 251).toByte() }

    @Test
    fun `leaves the row pending until the hash matches`() {
        val port = FakePort()
        val receiver = MediaStoreFileReceiver(port, "보고서.pdf")
        val data = payload(1024)

        assertEquals(0L, receiver.begin(offer(data.size), 0))
        assertTrue(port.pending, "the row must be pending while data is arriving")
        assertEquals("Download/Graft", port.insertedPath)
        assertEquals("application/pdf", port.insertedMime)

        receiver.write(BinaryFrame(1u, 0uL, data))
        assertTrue(port.pending, "still pending before verification")

        assertTrue(receiver.finish(sha256(data)))
        assertFalse(port.pending, "a verified file is published")
        assertTrue(port.cleared)
        assertTrue(port.sink.toByteArray().contentEquals(data))
    }

    @Test
    fun `deletes the row on hash mismatch`() {
        val port = FakePort()
        val receiver = MediaStoreFileReceiver(port, "bad.bin")
        val data = payload(64)

        receiver.begin(offer(data.size), 0)
        receiver.write(BinaryFrame(1u, 0uL, data))

        assertFalse(receiver.finish("0".repeat(64)))
        assertTrue(port.deleted, "a corrupt file must not remain visible or pending")
        assertFalse(port.cleared)
    }

    @Test
    fun `rejects a chunk at the wrong offset`() {
        val port = FakePort()
        val receiver = MediaStoreFileReceiver(port, "offset.bin")
        receiver.begin(offer(100), 0)

        val error = assertThrows<FileReceiverException> {
            receiver.write(BinaryFrame(1u, 50uL, payload(10)))
        }
        assertEquals(FileReceiverErrorKind.WRONG_OFFSET, error.kind)
    }

    @Test
    fun `cancel deletes the pending row`() {
        val port = FakePort()
        val receiver = MediaStoreFileReceiver(port, "cancel.bin")
        receiver.begin(offer(10), 0)

        receiver.cancel()

        assertTrue(port.deleted)
    }

    @Test
    fun `a resume request starts a fresh row`() {
        val port = FakePort()
        val receiver = MediaStoreFileReceiver(port, "resume.bin")

        // MediaStore cannot append to a pending row from a previous process.
        assertEquals(0L, receiver.begin(offer(1024), 512))
        assertEquals(0L, receiver.confirmedBytes)
    }

    @Test
    fun `factory produces receivers for a name`() {
        val factory = MediaStoreFileReceivingFactory(FakePort())
        assertTrue(factory.create("x.bin") is MediaStoreFileReceiver)
    }

    @Test
    fun `default relative path is Download Graft`() {
        assertEquals("Download/Graft", MediaStoreFileReceiver.DEFAULT_RELATIVE_PATH)
    }
}
