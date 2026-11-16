package kr.jjw.graft.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The analyzer's throttle and payload extraction are plain logic. Rendering and camera binding need
 * a device, but the part that can silently drop scans is testable here.
 */
class QRAnalyzerTest {
    private val payload = "graft://pair?v=1&h=192.168.0.12&p=47800&fp=Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ&t=042817&n=Mac"

    private fun qrLuminance(text: String, size: Int = 320): QRAnalyzer.Frame {
        val matrix: BitMatrix = MultiFormatWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return QRAnalyzer.Frame(argb = pixels, width = size, height = size)
    }

    @Test
    fun `decodes the pairing payload`() {
        val analyzer = QRAnalyzer(onResult = {})
        assertEquals(payload, analyzer.decode(qrLuminance(payload)))
    }

    @Test
    fun `returns null for a blank frame`() {
        val analyzer = QRAnalyzer(onResult = {})
        val blank = QRAnalyzer.Frame(
            argb = IntArray(64 * 64) { 0xFFFFFFFF.toInt() },
            width = 64,
            height = 64,
        )
        assertNull(analyzer.decode(blank))
    }

    @Test
    fun `ignores frames while disabled`() {
        var calls = 0
        val analyzer = QRAnalyzer(onResult = { calls += 1 })
        analyzer.enabled = false

        analyzer.analyse(qrLuminance(payload), nowMillis = 1_000)

        assertEquals(0, calls)
    }

    @Test
    fun `throttles decode attempts inside the window`() {
        val analyzer = QRAnalyzer(onResult = {})
        // A blank frame never decodes, so every attempt that passes the throttle stays visible.
        val blank = QRAnalyzer.Frame(
            argb = IntArray(64 * 64) { 0xFFFFFFFF.toInt() },
            width = 64,
            height = 64,
        )

        analyzer.analyse(blank, nowMillis = 1_000)
        analyzer.analyse(blank, nowMillis = 1_100)
        analyzer.analyse(blank, nowMillis = 1_299)
        analyzer.analyse(blank, nowMillis = 1_300)

        assertEquals(2, analyzer.decodeAttempts, "100 ms apart is dropped; 300 ms apart is kept")
    }

    @Test
    fun `a decode disables further scans`() {
        var calls = 0
        val analyzer = QRAnalyzer(onResult = { calls += 1 })
        val frame = qrLuminance(payload)

        analyzer.analyse(frame, nowMillis = 1_000)
        analyzer.analyse(frame, nowMillis = 5_000)

        assertEquals(1, calls, "one successful scan must not start a second pairing")
    }
}
