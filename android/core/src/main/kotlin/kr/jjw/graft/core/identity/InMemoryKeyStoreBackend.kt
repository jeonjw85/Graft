package kr.jjw.graft.core.identity

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * In-memory backend used by JVM tests. It generates real P-256 keys so certificate encoding is
 * exercised exactly as it is on a device.
 */
class InMemoryKeyStoreBackend : KeyStoreBackend {
    private val pairs = mutableMapOf<String, KeyPair>()
    private val certificates = mutableMapOf<String, ByteArray>()

    override fun loadOrCreateKey(alias: String): StoredKey {
        val pair = pairs.getOrPut(alias) {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            generator.generateKeyPair()
        }
        return StoredKey(pair.private, pair.public)
    }

    override fun storeCertificate(alias: String, der: ByteArray) {
        certificates[alias] = der
    }

    override fun loadCertificate(alias: String): X509Certificate? =
        certificates[alias]?.let { der ->
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

    fun publicKey(alias: String): java.security.PublicKey? = pairs[alias]?.public
}
