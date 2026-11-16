package kr.jjw.graft.files

import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer

/**
 * Destination for incoming file bytes.
 *
 * `:core` only knows this interface, so the coordinator stays free of Android types. The directory
 * implementation is used on the JVM and macOS; the Android app supplies a `MediaStore`-backed one.
 */
interface FileReceiving {
    /** Opens the destination and returns the offset the transfer continues from. */
    fun begin(offer: FileOffer, resumeOffset: Long): Long

    /** Writes one chunk. Must reject a chunk whose offset is not [confirmedBytes]. */
    fun write(frame: BinaryFrame)

    /** Verifies the digest and publishes the file. Returns false when the hash does not match. */
    fun finish(expectedSha256: String): Boolean

    fun cancel()

    val confirmedBytes: Long
}

/** Creates a receiver for a given file name. */
fun interface FileReceivingFactory {
    fun create(name: String): FileReceiving
}

/** Adapter that exposes [FileReceiver] through the interface. */
class DirectoryFileReceiving(private val receiver: FileReceiver) : FileReceiving {
    override fun begin(offer: FileOffer, resumeOffset: Long): Long =
        receiver.begin(offer, resumeOffset)

    override fun write(frame: BinaryFrame) = receiver.write(frame)

    override fun finish(expectedSha256: String): Boolean =
        runCatching { receiver.finish(expectedSha256) }.isSuccess

    override fun cancel() = receiver.cancel()

    override val confirmedBytes: Long get() = receiver.confirmedBytes

    companion object {
        fun factory(directory: java.io.File): FileReceivingFactory =
            FileReceivingFactory { DirectoryFileReceiving(FileReceiver(directory)) }
    }
}
