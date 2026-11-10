package kr.jjw.graft.clipboard

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kr.jjw.graft.core.Envelope

class ClipboardBridgeTest {
    private val bridge = ClipboardBridge()

    private fun textUpdate(body: String, kind: String = "text"): Envelope =
        Envelope.create(
            type = "clipboard.update",
            payload = buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
                put("body", kotlinx.serialization.json.JsonPrimitive(body))
                put("ts", kotlinx.serialization.json.JsonPrimitive(1))
            }
        )

    @Test
    fun `text update returns the body`() {
        assertEquals("안녕하세요 👋🏽", bridge.onMessage(textUpdate("안녕하세요 👋🏽")))
    }

    @Test
    fun `non-text kind is ignored`() {
        assertNull(bridge.onMessage(textUpdate("iVBORw0KGgo=", kind = "image")))
    }

    @Test
    fun `other message types are ignored`() {
        assertNull(bridge.onMessage(Envelope.create("hello", buildJsonObject { })))
    }

    @Test
    fun `oversized text is rejected with an error`() {
        val huge = "a".repeat(300 * 1024)

        assertNull(bridge.onMessage(textUpdate(huge)))
        assertNotNull(bridge.error())
    }

    @Test
    fun `empty clipboard produces no envelope`() {
        assertNull(bridge.outgoing(null))
        assertNull(bridge.outgoing(""))
    }

    @Test
    fun `outgoing carries kind body and timestamp`() {
        val envelope = assertNotNull(bridge.outgoing("hello", now = 1_762_752_000_000))

        assertEquals("clipboard.update", envelope.type)
        val payload = envelope.payload.jsonObject
        assertEquals("text", payload["kind"]?.jsonPrimitive?.content)
        assertEquals("hello", payload["body"]?.jsonPrimitive?.content)
        assertEquals("1762752000000", payload["ts"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outgoing rejects oversized clipboard`() {
        val huge = "b".repeat(300 * 1024)

        assertNull(bridge.outgoing(huge))
        assertNotNull(bridge.error())
    }
}
