package kr.jjw.graft.files

import java.io.File
import java.security.MessageDigest
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.Envelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TransferCoordinatorTest {
    @TempDir
    lateinit var directory: Path

    private val sentControl = mutableListOf<Envelope>()
    private val sentBinary = mutableListOf<ByteArray>()

    private fun coordinator(paired: Boolean = true) = TransferCoordinator(
        directory = directory.toFile(),
        isPaired = { paired },
        sendControl = { sentControl.add(it) },
        sendBinary = { sentBinary.add(it) },
    )

    private fun payload(count: Int) = ByteArray(count) { index -> (index % 251).toByte() }

    private fun offerEnvelope(tid: UInt, name: String, size: Int) = Envelope.create(
        "file.offer",
        buildJsonObject {
            put("tid", JsonPrimitive(tid.toLong()))
            put("name", JsonPrimitive(name))
            put("size", JsonPrimitive(size.toLong()))
            put("mime", JsonPrimitive("application/octet-stream"))
        }
    )

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    // MARK: - Receiving

    @Test
    fun `auto accepts from a paired peer`() {
        val data = payload(100 * 1024)
        val coordinator = coordinator()

        coordinator.onControl(offerEnvelope(1u, "incoming.bin", data.size))
        assertEquals("file.accept", sentControl.last().type)

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 64 * 1024, data.size)
            val frame = BinaryFrame(1u, offset.toULong(), data.copyOfRange(offset, end))
            coordinator.onBinary(frame.encoded())
            offset = end
        }

        coordinator.onControl(
            Envelope.create(
                "file.complete",
                buildJsonObject {
                    put("tid", JsonPrimitive(1L))
                    put("sha256", JsonPrimitive(sha256(data)))
                }
            )
        )

        assertTrue(coordinator.completed.contains(1u))
        assertTrue(File(directory.toFile(), "incoming.bin").readBytes().contentEquals(data))
        assertEquals(1.0, coordinator.progress[1u]?.fraction)
    }

    @Test
    fun `rejects an offer from an unpaired peer`() {
        val coordinator = coordinator(paired = false)

        coordinator.onControl(offerEnvelope(1u, "incoming.bin", 10))

        assertEquals("error", sentControl.last().type)
        assertTrue(directory.toFile().list()!!.isEmpty())
    }

    @Test
    fun `second offer with the same id is refused`() {
        val coordinator = coordinator()

        coordinator.onControl(offerEnvelope(1u, "a.bin", 10))
        coordinator.onControl(offerEnvelope(1u, "b.bin", 10))

        assertEquals("error", sentControl.last().type)
    }

    @Test
    fun `unsafe name is rejected`() {
        val coordinator = coordinator()

        coordinator.onControl(offerEnvelope(1u, "../../evil.sh", 10))

        assertEquals("error", sentControl.last().type)
        assertEquals(TransferFailureKind.UNSAFE_NAME, coordinator.failures[1u])
    }

    @Test
    fun `hash mismatch is reported`() {
        val data = payload(1024)
        val coordinator = coordinator()
        coordinator.onControl(offerEnvelope(1u, "bad.bin", data.size))
        coordinator.onBinary(BinaryFrame(1u, 0uL, data).encoded())

        coordinator.onControl(
            Envelope.create(
                "file.complete",
                buildJsonObject {
                    put("tid", JsonPrimitive(1L))
                    put("sha256", JsonPrimitive("0".repeat(64)))
                }
            )
        )

        assertEquals(TransferFailureKind.HASH_MISMATCH, coordinator.failures[1u])
    }

    @Test
    fun `wrong offset chunk fails the transfer`() {
        val coordinator = coordinator()
        coordinator.onControl(offerEnvelope(1u, "offset.bin", 1024))

        coordinator.onBinary(BinaryFrame(1u, 512uL, payload(10)).encoded())

        assertEquals(TransferFailureKind.WRONG_OFFSET, coordinator.failures[1u])
    }

    // MARK: - Sending

    @Test
    fun `offer sends control and frames on accept`() {
        val data = payload(100 * 1024)
        val file = File(directory.toFile(), "outgoing.bin")
        file.writeBytes(data)

        val coordinator = coordinator()
        coordinator.offer(file, 5u)

        assertEquals("file.offer", sentControl.last().type)

        coordinator.onControl(
            Envelope.create(
                "file.accept",
                buildJsonObject {
                    put("tid", JsonPrimitive(5L))
                    put("offset", JsonPrimitive(0L))
                }
            )
        )

        assertEquals(2, sentBinary.size)
        assertEquals("file.complete", sentControl.last().type)
        val hash = sentControl.last().payload.jsonObject["sha256"]?.jsonPrimitive?.content
        assertEquals(sha256(data), hash)
    }

    @Test
    fun `duplicate send id is refused`() {
        val file = File(directory.toFile(), "dup.bin")
        file.writeBytes(payload(10))

        val coordinator = coordinator()
        coordinator.offer(file, 5u)

        assertFalse(runCatching { coordinator.offer(file, 5u) }.isSuccess)
    }
}
