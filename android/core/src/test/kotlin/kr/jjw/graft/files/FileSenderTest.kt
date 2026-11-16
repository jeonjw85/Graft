package kr.jjw.graft.files

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileSenderTest {
    @TempDir
    lateinit var directory: Path

    private fun makeFile(bytes: Int, name: String = "sample.bin"): File {
        val file = File(directory.toFile(), name)
        file.writeBytes(ByteArray(bytes) { index -> (index % 251).toByte() })
        return file
    }

    @Test
    fun `streams exact chunks with monotonic offsets`() {
        val sender = FileSender(makeFile(200 * 1024))

        val frames = sender.frames(3, 0)

        assertEquals(listOf(64 * 1024, 64 * 1024, 64 * 1024, 8 * 1024), frames.map { it.data.size })
        assertEquals(
            listOf(0uL, 65536uL, 131072uL, 196608uL),
            frames.map { it.offset }
        )
        assertTrue(frames.all { it.transferId == 3u })
    }

    @Test
    fun `resume starts at the given offset`() {
        val sender = FileSender(makeFile(200 * 1024))

        val frames = sender.frames(1, 131_072)

        assertEquals(131_072uL, frames.first().offset)
        assertEquals(200 * 1024 - 131_072, frames.sumOf { it.data.size })
    }

    @Test
    fun `sha256 matches the whole file`() {
        val file = makeFile(150 * 1024)
        val sender = FileSender(file)

        val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, sender.sha256())
    }

    @Test
    fun `short file produces one frame`() {
        val sender = FileSender(makeFile(10))

        val frames = sender.frames(9, 0)

        assertEquals(1, frames.size)
        assertEquals(10, frames[0].data.size)
        assertEquals(0uL, frames[0].offset)
    }

    @Test
    fun `empty file produces no frames`() {
        val sender = FileSender(makeFile(0))

        assertTrue(sender.frames(1, 0).isEmpty())
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
                .joinToString("") { "%02x".format(it) },
            sender.sha256()
        )
    }

    @Test
    fun `offset beyond end is rejected`() {
        val sender = FileSender(makeFile(10))

        val error = assertThrows(FileSenderException::class.java) { sender.frames(1, 11) }
        assertEquals(FileSenderErrorKind.OFFSET_BEYOND_END, error.kind)
    }

    @Test
    fun `offer carries name and size`() {
        val sender = FileSender(makeFile(1024, "보고서.pdf"))

        val offer = sender.offer(5)

        assertEquals("보고서.pdf", offer.name)
        assertEquals(1024uL, offer.size)
        assertEquals(5u, offer.transferId)
    }

    @Test
    fun `chunk size is capped at the frame limit`() {
        val sender = FileSender(makeFile(100), chunkSize = 1024 * 1024)

        // A 1 MiB chunk size must be clamped to the 64 KiB frame limit, so a 100-byte file still
        // yields exactly one frame of 100 bytes.
        val frames = sender.frames(1, 0)
        assertEquals(1, frames.size)
        assertEquals(100, frames.first().data.size)
    }

    @Test
    fun `stream matches frames`() {
        val sender = FileSender(makeFile(150 * 1024))

        val streamed = sender.stream(2, 0).toList()
        val expected = sender.frames(2, 0)

        assertEquals(expected, streamed)
    }
}
