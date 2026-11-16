package kr.jjw.graft.files

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer

/**
 * The slice of `ContentResolver` this receiver needs.
 *
 * `ContentResolver` itself cannot be subclassed in a unit test, so the receiver depends on this
 * port and the app passes a thin adapter around the real resolver.
 */
interface MediaStorePort {
    /** Returns an opaque handle for the pending row, or null on failure. */
    fun insertPending(displayName: String, mime: String, relativePath: String): Any?

    fun openOutput(handle: Any): java.io.OutputStream?

    fun clearPending(handle: Any)

    fun delete(handle: Any)

    /** The content URI of an inserted row, so a finished file can be opened later. */
    fun locationOf(handle: Any): String?
}

/** Adapts a real [ContentResolver] to [MediaStorePort]. */
class ContentResolverMediaStorePort(private val resolver: ContentResolver) : MediaStorePort {
    override fun insertPending(displayName: String, mime: String, relativePath: String): Any? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    }

    override fun openOutput(handle: Any): java.io.OutputStream? =
        resolver.openOutputStream(handle as Uri)

    override fun clearPending(handle: Any) {
        resolver.update(
            handle as Uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }

    override fun delete(handle: Any) {
        resolver.delete(handle as Uri, null, null)
    }

    override fun locationOf(handle: Any): String? = (handle as? Uri)?.toString()
}

/**
 * Writes a received file into the public Downloads collection.
 *
 * The row is inserted with `IS_PENDING = 1`, so no other app sees a half-written file. The pending
 * flag is cleared only after the digest matches; on mismatch the row is deleted.
 */
class MediaStoreFileReceiver(
    private val port: MediaStorePort,
    private val name: String,
    private val relativePath: String = DEFAULT_RELATIVE_PATH,
) : FileReceiving {
    private var handle: Any? = null
    private var stream: java.io.OutputStream? = null
    private var digest = MessageDigest.getInstance("SHA-256")
    private var written = 0L

    override val confirmedBytes: Long get() = written

    override val destination: String?
        get() = handle?.let { port.locationOf(it) }

    override fun begin(offer: FileOffer, resumeOffset: Long): Long {
        // MediaStore cannot append to a pending row across process restarts, so a resume starts
        // over here. The coordinator treats the returned 0 as authoritative.
        val inserted = port.insertPending(name, offer.mime, relativePath)
            ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED, "MediaStore insert failed")

        handle = inserted
        val opened = port.openOutput(inserted)
        if (opened == null) {
            port.delete(inserted)
            handle = null
            throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED, "openOutputStream failed")
        }
        stream = opened
        digest = MessageDigest.getInstance("SHA-256")
        written = 0
        return 0
    }

    override fun write(frame: BinaryFrame) {
        val stream = this.stream
            ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        if (frame.offset.toLong() != written) {
            throw FileReceiverException(
                FileReceiverErrorKind.WRONG_OFFSET,
                "expected $written, got ${frame.offset}"
            )
        }
        stream.write(frame.data)
        digest.update(frame.data)
        written += frame.data.size
    }

    override fun finish(expectedSha256: String): Boolean {
        val stream = this.stream ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)
        val handle = this.handle ?: throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED)

        stream.flush()
        stream.close()
        this.stream = null

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            port.delete(handle)
            this.handle = null
            written = 0
            return false
        }

        port.clearPending(handle)
        return true
    }

    override fun cancel() {
        runCatching { stream?.close() }
        stream = null
        handle?.let { port.delete(it) }
        handle = null
        written = 0
    }

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Download/Graft"
    }
}

/** Factory the Android app passes to the coordinator. */
class MediaStoreFileReceivingFactory(
    private val port: MediaStorePort,
    private val relativePath: String = MediaStoreFileReceiver.DEFAULT_RELATIVE_PATH,
) : FileReceivingFactory {
    override fun create(name: String): FileReceiving =
        MediaStoreFileReceiver(port, name, relativePath)

    companion object {
        fun forResolver(
            resolver: ContentResolver,
            relativePath: String = MediaStoreFileReceiver.DEFAULT_RELATIVE_PATH,
        ): MediaStoreFileReceivingFactory =
            MediaStoreFileReceivingFactory(ContentResolverMediaStorePort(resolver), relativePath)
    }
}
