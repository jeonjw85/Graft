package kr.jjw.graft.files

import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.FileOffer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A receiver whose hash verdict can be flipped, standing in for a retried transfer. */
private class ToggleReceiving : FileReceiving {
    var accept = false
    var cancelCalls = 0
    var writes = 0

    override fun begin(offer: FileOffer, resumeOffset: Long): Long = 0
    override fun write(frame: BinaryFrame) {
        writes += 1
        if (frame.startOffset != 0L) {
            throw FileReceiverException(FileReceiverErrorKind.WRONG_OFFSET, "offset")
        }
    }
    override fun finish(expectedSha256: String): Boolean = accept
    override fun cancel() {
        cancelCalls += 1
    }
    override val confirmedBytes: Long get() = 0
}

class TransferRetryTest {
    private fun coordinator(receiving: FileReceiving) = TransferCoordinator(
        directory = java.io.File("/tmp/graft-test-unused"),
        isPaired = { true },
        sendControl = { },
        sendBinary = { },
        receiversFactory = FileReceivingFactory { receiving },
    )

    private fun offer(tid: UInt) = Envelope(
        id = "o$tid",
        ts = 1,
        type = "file.offer",
        payload = buildJsonObject {
            put("tid", JsonPrimitive(tid.toLong()))
            put("name", JsonPrimitive("a.bin"))
            put("size", JsonPrimitive(4))
            put("mime", JsonPrimitive("application/octet-stream"))
        },
    )

    private fun complete(tid: UInt) = Envelope(
        id = "c$tid",
        ts = 2,
        type = "file.complete",
        payload = buildJsonObject {
            put("tid", JsonPrimitive(tid.toLong()))
            put("sha256", JsonPrimitive("00"))
        },
    )

    @Test
    fun `a retried transfer that commits clears its failure`() {
        val receiving = ToggleReceiving()
        val coordinator = coordinator(receiving)

        coordinator.onControl(offer(1u))
        coordinator.onBinary(BinaryFrame(1u, 0uL, byteArrayOf(1, 2, 3, 4)).encoded())
        coordinator.onControl(complete(1u))
        assertTrue(coordinator.failures.containsKey(1u), "the first attempt fails the digest")

        receiving.accept = true
        coordinator.onControl(offer(1u))
        coordinator.onBinary(BinaryFrame(1u, 0uL, byteArrayOf(1, 2, 3, 4)).encoded())
        coordinator.onControl(complete(1u))

        assertFalse(coordinator.failures.containsKey(1u), "the retry succeeded")
        assertTrue(coordinator.completed.contains(1u))
    }

    @Test
    fun `a wrong offset cancels the receiver so no partial row is left behind`() {
        val receiving = ToggleReceiving()
        val coordinator = coordinator(receiving)

        coordinator.onControl(offer(2u))
        coordinator.onBinary(BinaryFrame(2u, 4uL, byteArrayOf(1, 2, 3, 4)).encoded())

        assertEquals(TransferFailureKind.WRONG_OFFSET, coordinator.failures[2u])
        assertEquals(1, receiving.cancelCalls)
    }

    @Test
    fun `ending a session cancels unfinished receivers`() {
        val receiving = ToggleReceiving()
        val coordinator = coordinator(receiving)

        coordinator.onControl(offer(3u))
        coordinator.cancelAll()

        assertEquals(1, receiving.cancelCalls)
    }
}
