package kr.jjw.graft.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MessageCodecTest {
    @Test
    fun `decode hello fixture`() {
        val envelope = MessageCodec.decode(FixtureLoader.bytes("hello"))

        assertEquals(1, envelope.v)
        assertEquals("hello", envelope.type)
        val payload = envelope.payload as JsonObject
        assertEquals("mac-7f3a9c", payload["deviceId"]?.jsonPrimitiveText())
        assertEquals("0.1.0", payload["appVersion"]?.jsonPrimitiveText())
    }

    @Test
    fun `round trip all fixtures`() {
        val names = FixtureLoader.names()
        assertEquals(15, names.size, "모든 픽스처를 라운드트립해야 한다")

        for (name in names) {
            val decoded = MessageCodec.decode(FixtureLoader.bytes(name))
            val reencoded = MessageCodec.encode(decoded)
            assertEquals(decoded, MessageCodec.decode(reencoded), name)
        }
    }

    @Test
    fun `reject unsupported version`() {
        val json = """{"v":2,"id":"x","type":"hello","ts":1,"payload":{}}""".toByteArray()

        val error = assertThrows<CodecException> { MessageCodec.decode(json) }
        assertEquals(CodecException.Kind.UNSUPPORTED_VERSION, error.kind)
    }

    @Test
    fun `oversized boundary`() {
        val atLimit = ByteArray(MessageCodec.MAX_CONTROL_FRAME_BYTES)
        assertEquals(
            CodecException.Kind.MALFORMED,
            assertThrows<CodecException> { MessageCodec.decode(atLimit) }.kind
        )

        val overLimit = ByteArray(MessageCodec.MAX_CONTROL_FRAME_BYTES + 1)
        assertEquals(
            CodecException.Kind.OVERSIZED,
            assertThrows<CodecException> { MessageCodec.decode(overLimit) }.kind
        )
    }

    @Test
    fun `malformed inputs fail cleanly`() {
        val inputs = listOf(
            """{"v":1""".toByteArray(),
            ByteArray(0),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        )

        for (input in inputs) {
            assertEquals(
                CodecException.Kind.MALFORMED,
                assertThrows<CodecException> { MessageCodec.decode(input) }.kind
            )
        }
    }

    @Test
    fun `preserve unicode`() {
        val text = "안녕하세요 👋🏽"
        val envelope = Envelope.create(
            type = "clipboard.update",
            payload = buildJsonObject {
                put("kind", "text")
                put("body", text)
                put("ts", 1_762_752_004_000)
            }
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(envelope))

        assertEquals(envelope.payload, decoded.payload)
        assertEquals(text, (decoded.payload as JsonObject)["body"]?.jsonPrimitiveText())
    }

    @Test
    fun `accept non object payload`() {
        val json = """{"v":1,"id":"x","type":"future.event","ts":1,"payload":[1,2,3]}""".toByteArray()

        val envelope = MessageCodec.decode(json)

        assertEquals("future.event", envelope.type)
        assertEquals(
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))),
            envelope.payload
        )
    }

    @Test
    fun `decode ignores unknown fields and types`() {
        val known = MessageCodec.decode(FixtureLoader.bytes("hello-unknown-field"))
        assertEquals("hello", known.type)
        assertEquals("mac-7f3a9c", (known.payload as JsonObject)["deviceId"]?.jsonPrimitiveText())

        val json = """{"v":1,"id":"x","type":"future.event","ts":1,"payload":{}}""".toByteArray()
        assertEquals("future.event", MessageCodec.decode(json).type)
    }

    @Test
    fun `encode fills defaults and omits null re`() {
        val before = System.currentTimeMillis()
        val envelope = Envelope.create(type = "ping")
        val after = System.currentTimeMillis()

        assertEquals(1, envelope.v)
        assertTrue(envelope.id.isNotBlank())
        assertTrue(envelope.ts in before..after)

        val encoded = MessageCodec.encode(envelope).decodeToString()
        assertFalse(encoded.contains("\"re\""))
        assertFalse(encoded.contains("null"))
    }

    @Test
    fun `reject invalid utf8 inside string`() {
        val prefix = """{"v":1,"id":"x","type":"clipboard.update","ts":1,"payload":{"body":"a""".toByteArray()
        val bytes = prefix + byteArrayOf(0xFF.toByte()) + """"}}""".toByteArray()

        assertEquals(
            CodecException.Kind.MALFORMED,
            assertThrows<CodecException> { MessageCodec.decode(bytes) }.kind
        )
    }

    @Test
    fun `reject missing required fields`() {
        val cases = listOf(
            """{"id":"x","type":"ping","ts":1,"payload":{}}""".toByteArray(),
            """{"v":1,"id":"x","type":"ping","ts":1}""".toByteArray()
        )

        for (bytes in cases) {
            assertEquals(
                CodecException.Kind.MALFORMED,
                assertThrows<CodecException> { MessageCodec.decode(bytes) }.kind
            )
        }
    }

    @Test
    fun `reject excessive nesting`() {
        val nested = "[".repeat(100) + "]".repeat(100)
        val json = """{"v":1,"id":"x","type":"future.event","ts":1,"payload":$nested}"""

        assertEquals(
            CodecException.Kind.MALFORMED,
            assertThrows<CodecException> { MessageCodec.decode(json.toByteArray()) }.kind
        )
    }

    @Test
    fun `reject lone surrogate escape`() {
        val json = """{"v":1,"id":"x","type":"clipboard.update","ts":1,"payload":{"body":"\ud83d"}}"""

        assertEquals(
            CodecException.Kind.MALFORMED,
            assertThrows<CodecException> { MessageCodec.decode(json.toByteArray()) }.kind
        )
    }

    @Test
    fun `accept valid surrogate pair escape`() {
        val json = """{"v":1,"id":"x","type":"clipboard.update","ts":1,"payload":{"body":"\ud83d\ude00"}}"""

        val envelope = MessageCodec.decode(json.toByteArray())

        assertEquals("😀", (envelope.payload as JsonObject)["body"]?.jsonPrimitiveText())
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveText(): String =
    (this as JsonPrimitive).content
