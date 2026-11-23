package kr.jjw.graft.files

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer

/** Keeps a received file name inside the destination directory. */
object SafeFilename {
    const val MAX_NAME_BYTES = 255

    /** Returns a name that cannot escape the destination, or null when the input is unusable. */
    fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if ('\u0000' in trimmed) return null
        if ('/' in trimmed || '\\' in trimmed) return null
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.any { it.code < 0x20 }) return null
        if (trimmed.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return null
        return trimmed
    }

    /** Appends " (2)", " (3)", ... before the extension until the name is free. */
    fun unique(directory: File, name: String): String {
        if (!File(directory, name).exists() && !File(directory, "$name.part").exists()) {
            return name
        }

        val suffixExt = name.substringAfterLast('.', missingDelimiterValue = "")
        val withoutExtension = if (suffixExt.isEmpty()) {
            name
        } else {
            name.removeSuffix(".$suffixExt")
        }

        // If the name already ends in " (n)", continue from n+1 instead of nesting suffixes.
        var base = withoutExtension
        var index = 2
        val suffix = Regex(""" \((\d+)\)$""").find(withoutExtension)
        if (suffix != null) {
            base = withoutExtension.substring(0, suffix.range.first)
            index = suffix.groupValues[1].toInt() + 1
        }

        while (index < 1000) {
            val candidate = if (suffixExt.isEmpty()) "$base ($index)" else "$base ($index).$suffixExt"
            if (!File(directory, candidate).exists() && !File(directory, "$candidate.part").exists()) {
                return candidate
            }
            index++
        }
        return "$base (${System.nanoTime()})"
    }
}

/** Finds bytes already on disk for a name, so a new connection can resume an earlier attempt. */
object ResumeLookup {
    fun offset(directory: File, name: String, size: Long): Long {
        val safe = SafeFilename.sanitize(name) ?: return 0
        val part = File(directory, "$safe.part")
        if (!part.exists()) return 0
        val partial = part.length()
        return if (partial in 1..size) partial else 0
    }
}

enum class FileReceiverErrorKind {
    UNSAFE_NAME,
    WRONG_OFFSET,
    NOT_STARTED,
    HASH_MISMATCH,
    ALREADY_FINISHED,
}

class FileReceiverException(
    val kind: FileReceiverErrorKind,
    message: String? = null,
) : Exception(message ?: kind.name)

/**
 * Writes an incoming transfer into [directory], resuming from an accepted offset.
 *
 * The file is written as `name.part` and only renamed when the digest of the bytes on disk matches.
 * An interrupted transfer leaves the `.part` file so the offset can be resumed.
 */
class FileReceiver(private val directory: File) {
    private var partFile: File? = null
    private var finalFile: File? = null
    private var handle: RandomAccessFile? = null
    private var written: Long = 0
    private var finished = false

    /** Opens (or creates) the partial file and returns the offset the transfer continues from. */
    fun begin(offer: FileOffer, resumeOffset: Long): Long {
        val name = SafeFilename.sanitize(offer.name)
            ?: throw FileReceiverException(FileReceiverErrorKind.UNSAFE_NAME)

        directory.mkdirs()

        // A resumed transfer must reuse its existing .part file, not pick a fresh name just
        // because the .part makes the plain name look taken.
        val resumable = resumeOffset > 0 &&
            File(directory, "$name.part").exists() &&
            File(directory, "$name.part").length() == resumeOffset
        val unique = if (resumable) name else SafeFilename.unique(directory, name)
        val part = File(directory, "$unique.part")
        val target = File(directory, unique)

        partFile = part
        finalFile = target
        finished = false

        if (part.exists() && resumeOffset > 0) {
            if (part.length() == resumeOffset) {
                handle = RandomAccessFile(part, "rw").apply { seek(resumeOffset) }
                written = resumeOffset
                return resumeOffset
            }
            part.delete()
        }

        part.delete()
        part.createNewFile()
        handle = RandomAccessFile(part, "rw").apply { setLength(0) }
        written = 0
        return 0
    }

    fun write(frame: BinaryFrame) {
        val handle = this.handle ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        if (finished) throw FileReceiverException(FileReceiverErrorKind.ALREADY_FINISHED)
        if (frame.offset.toLong() != written) {
            throw FileReceiverException(
                FileReceiverErrorKind.WRONG_OFFSET,
                "expected $written, got ${frame.offset}"
            )
        }

        handle.write(frame.data)
        written += frame.data.size
    }

    /** Verifies the digest of the bytes on disk and moves the file into place. */
    fun finish(expectedSha256: String): File {
        val handle = this.handle ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        val part = this.partFile ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        val target = this.finalFile ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        if (finished) throw FileReceiverException(FileReceiverErrorKind.ALREADY_FINISHED)

        handle.close()
        this.handle = null

        // Hash the bytes that actually landed on disk.
        val actual = digestOnDisk()
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            written = 0
            finished = true
            throw FileReceiverException(FileReceiverErrorKind.HASH_MISMATCH)
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw FileReceiverException(
                FileReceiverErrorKind.NOT_STARTED,
                "could not move ${part.name} into place"
            )
        }
        finished = true
        return target
    }

    fun digestOnDisk(): String {
        val part = partFile ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cancel() {
        runCatching { handle?.close() }
        handle = null
        finished = true
    }

    /** Closes the handle and deletes the partial file on disk (used when a transfer stalls). */
    fun discard() {
        cancel()
        partFile?.delete()
        written = 0
    }

    val confirmedBytes: Long get() = written
    val partialFile: File? get() = partFile
}
