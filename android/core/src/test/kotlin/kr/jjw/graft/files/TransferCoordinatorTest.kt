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

    private fun coordinator(
        paired: Boolean = true,
        records: TransferRecordStore? = null,
    ) = TransferCoordinator(
        directory = directory.toFile(),
        isPaired = { paired },
        sendControl = { sentControl.add(it) },
        sendBinary = { bytes -> sentBinary.add(bytes); true },
        records = records,
    )

    private fun records(): TransferRecordStore =
        TransferRecordStore(File(directory.toFile(), "transfers.json"))

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

    @Test
    fun `partial file from an earlier attempt is resumed`() {
        val data = payload(120 * 1024)
        val half = 64 * 1024

        val store = records()
        val first = coordinator(records = store)
        first.onControl(offerEnvelope(1u, "resume-me.bin", data.size))
        first.onBinary(BinaryFrame(1u, 0uL, data.copyOfRange(0, half)).encoded())
        first.onControl(
            Envelope.create(
                "file.cancel",
                buildJsonObject { put("tid", JsonPrimitive(1L)) }
            )
        )

        sentControl.clear()
        val second = coordinator(records = store)
        second.onControl(offerEnvelope(2u, "resume-me.bin", data.size))

        val accept = sentControl.last()
        assertEquals("file.accept", accept.type)
        assertEquals(
            half.toLong(),
            accept.payload.jsonObject["offset"]?.jsonPrimitive?.content?.toLong(),
            "the receiver must report the bytes it already has"
        )

        second.onBinary(BinaryFrame(2u, half.toULong(), data.copyOfRange(half, data.size)).encoded())
        second.onControl(
            Envelope.create(
                "file.complete",
                buildJsonObject {
                    put("tid", JsonPrimitive(2L))
                    put("sha256", JsonPrimitive(sha256(data)))
                }
            )
        )

        assertTrue(File(directory.toFile(), "resume-me.bin").readBytes().contentEquals(data))
    }

    @Test
    fun `a partial file without a record is not resumed`() {
        val data = payload(120 * 1024)
        val half = 64 * 1024

        // No record was written, so even though bytes exist on disk the receiver must start at 0;
        // otherwise an unrelated leftover file could be appended to.
        val first = coordinator()
        first.onControl(offerEnvelope(1u, "orphan.bin", data.size))
        first.onBinary(BinaryFrame(1u, 0uL, data.copyOfRange(0, half)).encoded())
        first.onControl(
            Envelope.create("file.cancel", buildJsonObject { put("tid", JsonPrimitive(1L)) })
        )

        sentControl.clear()
        val second = coordinator()
        second.onControl(offerEnvelope(2u, "orphan.bin", data.size))

        val offset = sentControl.last().payload.jsonObject["offset"]?.jsonPrimitive?.content
        assertEquals("0", offset)
    }

    @Test
    fun `hash mismatch clears the record`() {
        val store = records()
        val data = payload(1024)
        val coordinator = coordinator(records = store)

        coordinator.onControl(offerEnvelope(1u, "bad.bin", data.size))
        coordinator.onBinary(BinaryFrame(1u, 0uL, data).encoded())
        assertEquals(1, store.count())

        coordinator.onControl(
            Envelope.create(
                "file.complete",
                buildJsonObject {
                    put("tid", JsonPrimitive(1L))
                    put("sha256", JsonPrimitive("0".repeat(64)))
                }
            )
        )

        assertEquals(0, store.count(), "a corrupt partial must not stay resumable")
    }

    @Test
    fun `completed transfer clears the record`() {
        val store = records()
        val data = payload(1024)
        val coordinator = coordinator(records = store)

        coordinator.onControl(offerEnvelope(1u, "done.bin", data.size))
        coordinator.onBinary(BinaryFrame(1u, 0uL, data).encoded())
        coordinator.onControl(
            Envelope.create(
                "file.complete",
                buildJsonObject {
                    put("tid", JsonPrimitive(1L))
                    put("sha256", JsonPrimitive(sha256(data)))
                }
            )
        )

        assertEquals(0, store.count())
    }

    @Test
    fun `completed file with the same name gets a suffix`() {
        val data = payload(1024)
        val coordinator = coordinator()

        for (tid in listOf(1u, 2u)) {
            coordinator.onControl(offerEnvelope(tid, "twice.bin", data.size))
            coordinator.onBinary(BinaryFrame(tid, 0uL, data).encoded())
            coordinator.onControl(
                Envelope.create(
                    "file.complete",
                    buildJsonObject {
                        put("tid", JsonPrimitive(tid.toLong()))
                        put("sha256", JsonPrimitive(sha256(data)))
                    }
                )
            )
        }

        assertEquals(
            listOf("twice (2).bin", "twice.bin"),
            directory.toFile().list()!!.sorted()
        )
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
    fun `a rejected frame stops the send and records a failure`() {
        val file = File(directory.toFile(), "outgoing.bin")
        file.writeBytes(payload(100 * 1024))

        var accepted = true
        val coordinator = TransferCoordinator(
            directory = directory.toFile(),
            isPaired = { true },
            sendControl = { sentControl.add(it) },
            sendBinary = { accepted = false; false },
            backlog = { 0 },
            records = null,
        )
        coordinator.offer(file, 6u)
        coordinator.onControl(
            Envelope.create(
                "file.accept",
                buildJsonObject {
                    put("tid", JsonPrimitive(6L))
                    put("offset", JsonPrimitive(0L))
                }
            )
        )

        assertEquals(TransferFailureKind.READ_FAILED, coordinator.failures[6u])
        assertFalse(accepted, "the loop must stop at the first refused frame")
        assertEquals(0, sentControl.count { it.type == "file.complete" })
    }

    @Test
    fun `duplicate send id is refused`() {
        val file = File(directory.toFile(), "dup.bin")
        file.writeBytes(payload(10))

        val coordinator = coordinator()
        coordinator.offer(file, 5u)

        assertFalse(runCatching { coordinator.offer(file, 5u) }.isSuccess)
    }

    @Test
    fun `stalled receive times out and removes partial file`() {
        var now = 1_762_752_000_000L
        val store = records()
        val coordinator = TransferCoordinator(
            directory = directory.toFile(),
            isPaired = { true },
            sendControl = { sentControl.add(it) },
            sendBinary = { true },
            records = store,
            clock = { now },
        )

        coordinator.onControl(offerEnvelope(4u, "stalled.bin", 128 * 1024))
        coordinator.onBinary(BinaryFrame(4u, 0uL, payload(64 * 1024)).encoded())
        val partFile = File(directory.toFile(), "stalled.bin.part")
        assertTrue(partFile.exists())

        now += STALL_TIMEOUT_MILLIS - 1
        assertFalse(coordinator.checkStalls(now))

        now += 1
        assertTrue(coordinator.checkStalls(now))
        assertEquals(TransferFailureKind.STALLED, coordinator.failures[4u])
        assertFalse(partFile.exists())
        assertEquals(0, store.count())
        assertEquals("file.cancel", sentControl.last().type)
    }

    @Test
    fun `cancelAll flushes exact offset for resuming large partial`() {
        val data = payload(1_500 * 1024)
        val cut = 1_200 * 1024
        val store = records()

        val first = coordinator(records = store)
        first.onControl(offerEnvelope(1u, "large.bin", data.size))
        var offset = 0
        while (offset < cut) {
            val end = minOf(offset + 64 * 1024, cut)
            first.onBinary(BinaryFrame(1u, offset.toULong(), data.copyOfRange(offset, end)).encoded())
            offset = end
        }
        first.cancelAll()

        sentControl.clear()
        val second = coordinator(records = store)
        second.onControl(offerEnvelope(2u, "large.bin", data.size))
        val resumed = sentControl.last().payload.jsonObject["offset"]?.jsonPrimitive?.content?.toLong()
        assertEquals(cut.toLong(), resumed)
    }

    @Test
    fun `async sendRunner streams off the control loop and notifies progress`() {
        val data = payload(200 * 1024)
        val file = File(directory.toFile(), "async.bin")
        file.writeBytes(data)

        val latch = java.util.concurrent.CountDownLatch(1)
        var progressCalls = 0
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val coordinator = TransferCoordinator(
                directory = directory.toFile(),
                isPaired = { true },
                sendControl = { envelope ->
                    sentControl.add(envelope)
                    if (envelope.type == "file.complete") latch.countDown()
                },
                sendBinary = { bytes -> sentBinary.add(bytes); true },
                sendRunner = { block -> executor.execute(block) },
                onProgressChanged = { progressCalls += 1 },
            )

            coordinator.offer(file, 7u, mime = "application/pdf")
            assertEquals("application/pdf", sentControl.last().payload.jsonObject["mime"]?.jsonPrimitive?.content)

            coordinator.onControl(
                Envelope.create(
                    "file.accept",
                    buildJsonObject {
                        put("tid", JsonPrimitive(7L))
                        put("offset", JsonPrimitive(0L))
                    }
                )
            )

            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(progressCalls > 0)
            assertTrue(coordinator.completed.contains(7u))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancel discards partial file and notifies peer`() {
        val store = records()
        val coordinator = coordinator(records = store)

        coordinator.onControl(offerEnvelope(11u, "abort.bin", 200 * 1024))
        coordinator.onBinary(BinaryFrame(11u, 0uL, payload(64 * 1024)).encoded())
        val partFile = File(directory.toFile(), "abort.bin.part")
        assertTrue(partFile.exists())

        coordinator.cancel(11u)

        assertFalse(coordinator.progress.containsKey(11u))
        assertFalse(partFile.exists())
        assertEquals(0, store.count())
        assertEquals("file.cancel", sentControl.last().type)
    }
}
