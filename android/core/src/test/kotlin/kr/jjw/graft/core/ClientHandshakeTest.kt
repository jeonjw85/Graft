package kr.jjw.graft.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientHandshakeTest {
    @Test
    fun `an unpaired client asks to pair with the typed code`() {
        val envelope = ClientHandshake.initial(
            paired = false,
            token = "627324",
            deviceId = "device-1",
            name = "Pixel",
        )

        assertEquals("pair.request", envelope.type)
        val payload = envelope.payload.jsonObject
        assertEquals("627324", payload["token"]?.jsonPrimitive?.content)
        assertEquals("device-1", payload["deviceId"]?.jsonPrimitive?.content)
        assertEquals("Pixel", payload["name"]?.jsonPrimitive?.content)
        assertEquals("android", payload["platform"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a paired client says hello instead of pairing again`() {
        val envelope = ClientHandshake.initial(
            paired = true,
            token = "ignored",
            deviceId = "device-1",
            name = "Pixel",
        )

        assertEquals("hello", envelope.type)
        assertEquals("1", envelope.payload.jsonObject["proto"]?.jsonPrimitive?.content)
        val caps = envelope.payload.jsonObject["caps"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertTrue(caps?.contains("device.status") == true)
        assertTrue(caps?.contains("device.ring") == true)
        assertTrue(caps?.contains("notification.reply") == true)
        assertTrue(caps?.contains("notification.dismiss") == true)
    }
}
