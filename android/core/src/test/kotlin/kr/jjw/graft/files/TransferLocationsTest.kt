package kr.jjw.graft.files

import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.FileOffer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class CommittingReceiving(
    private val result: Boolean,
    private val dest: String?,
) : FileReceiving {
    override fun begin(offer: FileOffer, resumeOffset: Long): Long = 0
    override fun write(frame: BinaryFrame) = Unit
    override fun finish(expectedSha256: String): Boolean = result
    override fun cancel() = Unit
    override val confirmedBytes: Long get() = 0
    override val destination: String? get() = dest
}

class TransferLocationsTest {
    private fun coordinator(receiving: FileReceiving) = TransferCoordinator(
        directory = java.io.File("/tmp/graft-test-unused"),
        isPaired = { true },
        sendControl = { },
        sendBinary = { },
        receiversFactory = FileReceivingFactory { receiving },
    )

    private fun offer(tid: UInt, size: Long) = Envelope(
        id = "t$tid",
        ts = 1_000,
        type = "file.offer",
        payload = buildJsonObject {
            put("tid", JsonPrimitive(tid.toLong()))
            put("name", JsonPrimitive("a.bin"))
            put("size", JsonPrimitive(size))
            put("mime", JsonPrimitive("application/octet-stream"))
        },
    )

    private fun complete(tid: UInt) = Envelope(
        id = "c$tid",
        ts = 2_000,
        type = "file.complete",
        payload = buildJsonObject {
            put("tid", JsonPrimitive(tid.toLong()))
            put("sha256", JsonPrimitive("00"))
        },
    )

    @Test
    fun `a committed transfer records its destination`() {
        val coordinator = coordinator(CommittingReceiving(result = true, dest = "content://downloads/1"))

        coordinator.onControl(offer(1u, 4))
        coordinator.onBinary(BinaryFrame(1u, 0uL, byteArrayOf(1, 2, 3, 4)).encoded())
        coordinator.onControl(complete(1u))

        assertEquals("content://downloads/1", coordinator.locations[1u])
        assertTrue(coordinator.completed.contains(1u))
    }

    @Test
    fun `a hash mismatch records no destination`() {
        val coordinator = coordinator(CommittingReceiving(result = false, dest = "content://downloads/1"))

        coordinator.onControl(offer(2u, 4))
        coordinator.onBinary(BinaryFrame(2u, 0uL, byteArrayOf(1, 2, 3, 4)).encoded())
        coordinator.onControl(complete(2u))

        assertFalse(coordinator.locations.containsKey(2u))
        assertEquals(TransferFailureKind.HASH_MISMATCH, coordinator.failures[2u])
    }
}
