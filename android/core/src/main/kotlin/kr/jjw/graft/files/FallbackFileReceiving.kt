package kr.jjw.graft.files

import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer

/**
 * Tries the primary destination and, when opening it fails, quietly writes to the fallback.
 *
 * Android inserts the MediaStore row inside `begin`, so a denial (no permission, provider down) only
 * shows up there. Falling back keeps the transfer working instead of failing the whole receive, and
 * `usedFallback` lets the UI say where the file actually went.
 */
class FallbackFileReceiving(
    private val primary: FileReceiving,
    private val fallback: FileReceiving,
) : FileReceiving {
    private var active: FileReceiving = primary

    /** True once the primary refused the transfer. */
    var usedFallback: Boolean = false
        private set

    override fun begin(offer: FileOffer, resumeOffset: Long): Long = try {
        primary.begin(offer, resumeOffset)
    } catch (error: FileReceiverException) {
        usedFallback = true
        active = fallback
        fallback.begin(offer, resumeOffset)
    }

    override fun write(frame: BinaryFrame) = active.write(frame)

    override fun finish(expectedSha256: String): Boolean = active.finish(expectedSha256)

    override fun cancel() = active.cancel()

    override val confirmedBytes: Long get() = active.confirmedBytes

    override val destination: String? get() = active.destination
}

/** Builds a [FallbackFileReceiving] for every file name. */
object FallbackFileReceivingFactory {
    fun of(primary: FileReceivingFactory, fallback: FileReceivingFactory): FileReceivingFactory =
        FileReceivingFactory { name ->
            FallbackFileReceiving(
                primary = primary.create(name),
                fallback = fallback.create(name),
            )
        }
}
