package kr.jjw.graft.files

import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.FileOffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeReceiving(
    private val dest: String? = null,
    private val failBegin: Boolean = false,
    private val beginThrows: Exception? = null,
) : FileReceiving {
    var beginCalls = 0
    var writes = 0
    var finishes = 0
    var cancels = 0

    override fun begin(offer: FileOffer, resumeOffset: Long): Long {
        beginCalls += 1
        beginThrows?.let { throw it }
        if (failBegin) throw FileReceiverException(FileReceiverErrorKind.NOT_STARTED, "no")
        return 0
    }

    override fun write(frame: BinaryFrame) {
        writes += 1
    }

    override fun finish(expectedSha256: String): Boolean {
        finishes += 1
        return true
    }

    override fun cancel() {
        cancels += 1
    }

    override val confirmedBytes: Long get() = 0
    override val destination: String? get() = dest
}

class FallbackFileReceivingTest {
    private val offer = FileOffer(transferId = 1u, name = "a.bin", size = 4uL, mime = "application/octet-stream")
    private val frame = BinaryFrame(1u, 0uL, byteArrayOf(1, 2, 3, 4))

    @Test
    fun `a failing primary hands over to the fallback`() {
        val primary = FakeReceiving(failBegin = true)
        val fallback = FakeReceiving(dest = "/cache/received/a.bin")
        val receiving = FallbackFileReceiving(primary, fallback)

        receiving.begin(offer, 0)

        assertTrue(receiving.usedFallback)
        assertEquals(1, fallback.beginCalls)
        assertEquals("/cache/received/a.bin", receiving.destination)
    }

    @Test
    fun `a platform failure also hands over to the fallback`() {
        val primary = FakeReceiving(beginThrows = SecurityException("no downloads access"))
        val fallback = FakeReceiving(dest = "/cache/received/a.bin")
        val receiving = FallbackFileReceiving(primary, fallback)

        receiving.begin(offer, 0)

        assertTrue(receiving.usedFallback)
        assertEquals(1, fallback.beginCalls)
    }

    @Test
    fun `a working primary never touches the fallback`() {
        val primary = FakeReceiving(dest = "content://downloads/1")
        val fallback = FakeReceiving()
        val receiving = FallbackFileReceiving(primary, fallback)

        receiving.begin(offer, 0)

        assertFalse(receiving.usedFallback)
        assertEquals(0, fallback.beginCalls)
        assertEquals("content://downloads/1", receiving.destination)
    }

    @Test
    fun `writes after the hand-over go only to the fallback`() {
        val primary = FakeReceiving(failBegin = true)
        val fallback = FakeReceiving()
        val receiving = FallbackFileReceiving(primary, fallback)

        receiving.begin(offer, 0)
        receiving.write(frame)

        assertEquals(0, primary.writes)
        assertEquals(1, fallback.writes)
    }

    @Test
    fun `finish and cancel follow the active receiver`() {
        val primary = FakeReceiving(failBegin = true)
        val fallback = FakeReceiving()
        val receiving = FallbackFileReceiving(primary, fallback)

        receiving.begin(offer, 0)
        receiving.finish("sha")
        receiving.cancel()

        assertEquals(1, fallback.finishes)
        assertEquals(1, fallback.cancels)
        assertEquals(0, primary.finishes)
    }

    @Test
    fun `the factory builds a wrapped receiver per name`() {
        val factory = FallbackFileReceivingFactory.of(
            primary = FileReceivingFactory { FakeReceiving(failBegin = true) },
            fallback = FileReceivingFactory { FakeReceiving(dest = "/cache/x") },
        )

        val receiving = factory.create("x")

        assertTrue(receiving is FallbackFileReceiving)
    }
}
