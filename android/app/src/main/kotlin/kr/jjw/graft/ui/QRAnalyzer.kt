package kr.jjw.graft.ui

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes a pairing QR code from camera frames.
 *
 * Only one scan is delivered: a second successful decode would start a second pairing, and the
 * camera keeps producing frames as long as the screen is open.
 */
class QRAnalyzer(
    private val onResult: (String) -> Unit,
    private val throttleMillis: Long = 300,
) : ImageAnalysis.Analyzer {

    /** Set false once a code has been handled, or while the permission dialog is open. */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var lastAttemptAt: Long = 0

    @Volatile
    private var delivered = false

    /** Counts decoder invocations so the throttle is observable without a camera. */
    @Volatile
    internal var decodeAttempts: Int = 0
        private set

    /**
     * A frame in the form the decoder needs: ARGB pixels plus size.
     *
     * Kept free of `Bitmap` so the decode path runs in a plain JVM test; the camera adapter converts
     * once at the edge.
     */
    class Frame(val argb: IntArray, val width: Int, val height: Int)

    /** Decodes without the throttle, for tests and one-off use. */
    fun decode(frame: Frame): String? {
        val luminance = ByteArray(frame.argb.size)
        for (index in frame.argb.indices) {
            val pixel = frame.argb[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            luminance[index] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
        }

        val source = PlanarYUVLuminanceSource(
            luminance,
            frame.width,
            frame.height,
            0,
            0,
            frame.width,
            frame.height,
            false,
        )
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (error: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** Throttled entry point used by the camera; the clock is injected for tests. */
    fun analyse(frame: Frame, nowMillis: Long) {
        if (!enabled || delivered) return
        if (nowMillis - lastAttemptAt < throttleMillis) return
        lastAttemptAt = nowMillis
        decodeAttempts += 1

        val text = decode(frame) ?: return
        delivered = true
        enabled = false
        onResult(text)
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled || delivered) return
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            analyse(Frame(pixels, bitmap.width, bitmap.height), System.currentTimeMillis())
        } catch (error: Exception) {
            // A malformed frame is not worth crashing the preview; the next frame tries again.
        } finally {
            image.close()
        }
    }
}
