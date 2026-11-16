package kr.jjw.graft.files

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer

enum class FileSenderErrorKind {
    UNREADABLE,
    OFFSET_BEYOND_END,
}

class FileSenderException(val kind: FileSenderErrorKind, message: String? = null) :
    Exception(message ?: kind.name)

/**
 * Streams a file as 64 KiB binary frames and computes its SHA-256.
 *
 * [open] is a factory so a resumed send can reopen the stream at a new offset without holding the
 * previous one.
 */
class FileSender(
    private val open: () -> InputStream,
    val name: String,
    val size: Long,
    private val chunkSize: Int = 64 * 1024,
) {
    constructor(file: File, chunkSize: Int = 64 * 1024) : this(
        open = { file.inputStream() },
        name = file.name,
        size = file.length(),
        chunkSize = chunkSize,
    )

    init {
        require(size >= 0) { "size must be non-negative" }
    }

    private val effectiveChunk = chunkSize.coerceAtMost(BinaryFrame.MAX_DATA_SIZE)

    fun offer(transferId: Int, mime: String = "application/octet-stream") = FileOffer(
        transferId = transferId.toUInt(),
        name = name,
        size = size.toULong(),
        mime = mime,
    )

    /** Reads the whole file from [offset] into memory. Used when the caller already buffers. */
    fun frames(transferId: Int, offset: Long = 0): List<BinaryFrame> {
        if (offset > size) throw FileSenderException(FileSenderErrorKind.OFFSET_BEYOND_END)

        val frames = mutableListOf<BinaryFrame>()
        var position = offset
        open().use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(effectiveChunk)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                frames.add(
                    BinaryFrame(
                        transferId = transferId.toUInt(),
                        offset = position.toULong(),
                        data = buffer.copyOf(read),
                    )
                )
                position += read
            }
        }
        return frames
    }

    /** Yields frames one at a time so a large file is never held in memory. */
    fun stream(transferId: Int, offset: Long = 0): Sequence<BinaryFrame> {
        if (offset > size) throw FileSenderException(FileSenderErrorKind.OFFSET_BEYOND_END)

        return sequence {
            var position = offset
            open().use { input ->
                skipFully(input, offset)
                val buffer = ByteArray(effectiveChunk)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    yield(
                        BinaryFrame(
                            transferId = transferId.toUInt(),
                            offset = position.toULong(),
                            data = buffer.copyOf(read),
                        )
                    )
                    position += read
                }
            }
        }
    }

    /** SHA-256 of the whole file, read from the source. */
    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buffer = ByteArray(effectiveChunk)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip() may return 0 on some streams; fall back to reading.
                if (input.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
