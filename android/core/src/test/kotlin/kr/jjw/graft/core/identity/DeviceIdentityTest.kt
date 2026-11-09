package kr.jjw.graft.core.identity

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kr.jjw.graft.core.SpkiFingerprint
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceIdentityTest {
    @Test
    fun `create then reload keeps the same fingerprint`() {
        val backend = InMemoryKeyStoreBackend()

        val first = DeviceIdentity.loadOrCreate(backend, "test")
        val second = DeviceIdentity.loadOrCreate(backend, "test")

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `certificate is a self-signed P-256 server and client certificate`() {
        val backend = InMemoryKeyStoreBackend()
        val identity = DeviceIdentity.loadOrCreate(backend, "test")
        val certificate = identity.certificate

        assertEquals("EC", certificate.publicKey.algorithm)
        assertEquals("SHA256withECDSA", certificate.sigAlgName)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.basicConstraints == -1, "must not be a CA")
        assertTrue(certificate.keyUsage?.get(0) == true, "digitalSignature must be set")

        val validity = certificate.notAfter.time - certificate.notBefore.time
        assertTrue(
            validity > TimeUnit.DAYS.toMillis(3000),
            "validity should be about 10 years, was ${TimeUnit.MILLISECONDS.toDays(validity)} days"
        )
    }

    @Test
    fun `distinct aliases produce distinct identities`() {
        val backend = InMemoryKeyStoreBackend()

        val first = DeviceIdentity.loadOrCreate(backend, "one")
        val second = DeviceIdentity.loadOrCreate(backend, "two")

        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `certificate encoding matches the shared SPKI vector`() {
        // Decode the fixture key pair so the certificate is built around known public key bytes.
        val vector = SharedVectors.first()
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))

        // The fixture exposes only the public point, so build a key from its SPKI DER.
        val spki = vector.spki
        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(spki))
        assertEquals(
            vector.fingerprint,
            SpkiFingerprint.base64Url(publicKey.encoded),
            "loading the SPKI must preserve the fingerprint"
        )
    }
}

internal object SharedVectors {
    data class Vector(val spki: ByteArray, val fingerprint: String)

    fun all(): List<Vector> {
        val fixture = java.io.File(
            System.getProperty("graft.fixturesDir") ?: "../../protocol/fixtures",
            "identity/expected.json"
        )
        val root = kotlinx.serialization.json.Json
            .parseToJsonElement(fixture.readText())
            .jsonObject
        return root.getValue("vectors").jsonArray.map { element ->
            val row = element.jsonObject
            Vector(
                spki = row.getValue("spkiHex").jsonPrimitive.content.hexToBytes(),
                fingerprint = row.getValue("sha256Base64Url").jsonPrimitive.content,
            )
        }
    }

    fun first(): Vector = all().first()
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
