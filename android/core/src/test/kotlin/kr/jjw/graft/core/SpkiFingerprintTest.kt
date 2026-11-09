package kr.jjw.graft.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpkiFingerprintTest {
    private data class Vector(val name: String, val spki: ByteArray, val fingerprint: String)

    private fun loadVectors(): List<Vector> {
        val file = File(
            System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures",
            "identity/expected.json"
        )
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("vectors").jsonArray.map { element ->
            val row = element.jsonObject
            Vector(
                name = row.getValue("name").jsonPrimitive.content,
                spki = row.getValue("spkiHex").jsonPrimitive.content.hexToBytes(),
                fingerprint = row.getValue("sha256Base64Url").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `shared identity vectors match`() {
        val vectors = loadVectors()
        assertEquals(2, vectors.size, "both languages must read the same vectors")

        for (vector in vectors) {
            assertEquals(
                vector.fingerprint,
                SpkiFingerprint.base64Url(vector.spki),
                "vector ${vector.name}"
            )
        }
    }

    @Test
    fun `upper and lower case hex decode identically`() {
        val vectors = loadVectors()
        for (vector in vectors) {
            val upperHex = vector.spki.joinToString("") { "%02X".format(it) }
            assertEquals(
                vector.fingerprint,
                SpkiFingerprint.base64Url(upperHex.hexToBytes())
            )
        }
    }
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
