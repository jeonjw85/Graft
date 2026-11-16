package kr.jjw.graft.files

import java.io.File
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SafeFilenameTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `rejects path traversal and unusable names`() {
        assertNull(SafeFilename.sanitize("../../evil.sh"))
        assertNull(SafeFilename.sanitize(".."))
        assertNull(SafeFilename.sanitize("."))
        assertNull(SafeFilename.sanitize("a/b"))
        assertNull(SafeFilename.sanitize("a\\b"))
        assertNull(SafeFilename.sanitize("bad\u0000name"))
        assertNull(SafeFilename.sanitize(""))
        assertNull(SafeFilename.sanitize("   "))
    }

    @Test
    fun `rejects control characters and overlong names`() {
        assertNull(SafeFilename.sanitize("bell\u0007.txt"))
        assertNull(SafeFilename.sanitize("a".repeat(300)))
    }

    @Test
    fun `allows unicode names`() {
        assertEquals("보고서 2026.pdf", SafeFilename.sanitize("보고서 2026.pdf"))
        assertEquals("사진 👋🏽.png", SafeFilename.sanitize("사진 👋🏽.png"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("report.pdf", SafeFilename.sanitize("  report.pdf  "))
    }

    @Test
    fun `collision suffix`() {
        val dir = directory.toFile()
        assertEquals("a.txt", SafeFilename.unique(dir, "a.txt"))

        File(dir, "a.txt").createNewFile()
        assertEquals("a (2).txt", SafeFilename.unique(dir, "a.txt"))
    }

    @Test
    fun `suffix continues instead of nesting`() {
        val dir = directory.toFile()
        File(dir, "notes (2)").createNewFile()
        assertEquals("notes (3)", SafeFilename.unique(dir, "notes (2)"))
    }
}

class FileReceiverTest {
    @TempDir
    lateinit var directory: Path

    private fun dir() = directory.toFile()

    private fun payload(count: Int): ByteArray =
        ByteArray(count) { index -> (index % 251).toByte() }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun offer(name: String, size: Int) =
        FileOffer(transferId = 1u, name = name, size = size.toULong(), mime = "application/octet-stream")

    private fun frame(offset: Long, data: ByteArray) =
        BinaryFrame(transferId = 1u, offset = offset.toULong(), data = data)

    @Test
    fun `writes chunks and commits on matching hash`() {
        val data = payload(200 * 1024)
        val receiver = FileReceiver(dir())
        assertEquals(0L, receiver.begin(offer("sample.bin", data.size), 0))

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 64 * 1024, data.size)
            receiver.write(frame(offset.toLong(), data.copyOfRange(offset, end)))
            offset = end
        }

        val url = receiver.finish(sha256(data))
        assertTrue(url.readBytes().contentEquals(data))
        assertEquals(listOf("sample.bin"), dir().list()!!.sorted())
    }

    @Test
    fun `resume appends from the accepted offset`() {
        val data = payload(200 * 1024)
        val firstHalf = 128 * 1024

        val first = FileReceiver(dir())
        first.begin(offer("resume.bin", data.size), 0)
        var offset = 0
        while (offset < firstHalf) {
            val end = minOf(offset + 64 * 1024, firstHalf)
            first.write(frame(offset.toLong(), data.copyOfRange(offset, end)))
            offset = end
        }
        first.cancel()

        val second = FileReceiver(dir())
        assertEquals(firstHalf.toLong(), second.begin(offer("resume.bin", data.size), firstHalf.toLong()))

        offset = firstHalf
        while (offset < data.size) {
            val end = minOf(offset + 64 * 1024, data.size)
            second.write(frame(offset.toLong(), data.copyOfRange(offset, end)))
            offset = end
        }

        val url = second.finish(sha256(data))
        assertTrue(url.readBytes().contentEquals(data))
    }

    @Test
    fun `hash mismatch deletes the partial file`() {
        val data = payload(1024)
        val receiver = FileReceiver(dir())
        receiver.begin(offer("bad.bin", data.size), 0)
        receiver.write(frame(0, data))

        val error = assertThrows(FileReceiverException::class.java) {
            receiver.finish("0".repeat(64))
        }
        assertEquals(FileReceiverErrorKind.HASH_MISMATCH, error.kind)
        assertTrue(dir().list()!!.isEmpty())
    }

    @Test
    fun `wrong offset is rejected`() {
        val data = payload(1024)
        val receiver = FileReceiver(dir())
        receiver.begin(offer("offset.bin", data.size), 0)

        val error = assertThrows(FileReceiverException::class.java) {
            receiver.write(frame(512, data))
        }
        assertEquals(FileReceiverErrorKind.WRONG_OFFSET, error.kind)
    }

    @Test
    fun `unsafe name is rejected`() {
        val receiver = FileReceiver(dir())
        val error = assertThrows(FileReceiverException::class.java) {
            receiver.begin(offer("../../evil.sh", 1), 0)
        }
        assertEquals(FileReceiverErrorKind.UNSAFE_NAME, error.kind)
    }

    @Test
    fun `interrupted transfer leaves a resumable part file`() {
        val data = payload(100 * 1024)
        val receiver = FileReceiver(dir())
        receiver.begin(offer("partial.bin", data.size), 0)
        receiver.write(frame(0, data))
        receiver.cancel()

        assertEquals(listOf("partial.bin.part"), dir().list()!!.sorted())
        assertEquals(data.size.toLong(), receiver.confirmedBytes)
    }

    @Test
    fun `hash is computed from the bytes on disk`() {
        val data = payload(4096)
        val receiver = FileReceiver(dir())
        receiver.begin(offer("disk.bin", data.size), 0)
        receiver.write(frame(0, data))

        // Truncate behind the receiver's back; finish must notice.
        val part = receiver.partialFile!!
        java.io.RandomAccessFile(part, "rw").use { it.setLength(1024) }

        assertThrows(FileReceiverException::class.java) { receiver.finish(sha256(data)) }
    }

    @Test
    fun `resume with a mismatched partial size starts over`() {
        val receiver = FileReceiver(dir())
        receiver.begin(offer("mismatch.bin", 4096), 0)
        receiver.write(frame(0, payload(100)))
        receiver.cancel()

        val second = FileReceiver(dir())
        assertEquals(0L, second.begin(offer("mismatch.bin", 4096), 2048))
        assertEquals(0L, second.confirmedBytes)
    }
}
