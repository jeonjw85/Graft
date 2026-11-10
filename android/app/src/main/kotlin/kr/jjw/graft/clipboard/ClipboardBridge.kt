package kr.jjw.graft.clipboard

import kr.jjw.graft.core.Envelope
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Moves clipboard text between the connection and the Android clipboard.
 *
 * Android 10+ blocks background clipboard access, so this class never polls: the UI reads the
 * clipboard and sends on demand, and writes only while the app is in the foreground.
 */
class ClipboardBridge {
    private var lastError: String? = null

    /** Returns the text to copy, or null when the frame is not a text clipboard update. */
    fun onMessage(envelope: Envelope): String? {
        if (envelope.type != "clipboard.update") return null

        val payload = runCatching { envelope.payload.jsonObject }.getOrNull() ?: return null
        val kind = payload["kind"]?.jsonPrimitive?.content ?: return null
        if (kind != "text") return null
        val body = payload["body"]?.jsonPrimitive?.content ?: return null

        if (body.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            lastError = "Clipboard text is larger than 256 KiB and was not copied"
            return null
        }

        lastError = null
        return body
    }

    /** Builds an envelope for [text], or null when it is empty or too large to send. */
    fun outgoing(text: String?, now: Long = System.currentTimeMillis()): Envelope? {
        if (text.isNullOrEmpty()) return null
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            lastError = "Clipboard text is larger than 256 KiB and was not sent"
            return null
        }

        return Envelope.create(
            type = "clipboard.update",
            payload = kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("text"))
                put("body", kotlinx.serialization.json.JsonPrimitive(text))
                put("ts", kotlinx.serialization.json.JsonPrimitive(now))
            }
        )
    }

    fun error(): String? = lastError

    companion object {
        const val MAX_BYTES = 256 * 1024
    }
}
