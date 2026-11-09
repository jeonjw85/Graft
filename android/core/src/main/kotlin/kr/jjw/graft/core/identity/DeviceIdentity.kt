package kr.jjw.graft.core.identity

import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import kr.jjw.graft.core.SpkiFingerprint
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A self-signed P-256 identity used for TLS.
 *
 * The private key stays inside [KeyStoreBackend]; this class only holds a handle, which is what
 * lets a non-extractable Android Keystore key work unchanged.
 */
class DeviceIdentity private constructor(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
    val fingerprint: String,
) {
    companion object {
        private const val VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
        private const val COMMON_NAME = "Graft Device"

        fun loadOrCreate(backend: KeyStoreBackend, alias: String = "graft-identity"): DeviceIdentity {
            val key = backend.loadOrCreateKey(alias)
            val certificate = backend.loadCertificate(alias)
                ?: createCertificate(key).also { created ->
                    backend.storeCertificate(alias, created.encoded)
                }
            return DeviceIdentity(
                certificate = certificate,
                privateKey = key.privateKey,
                fingerprint = SpkiFingerprint.base64Url(certificate.publicKey.encoded),
            )
        }

        /**
         * Builds a certificate for an arbitrary public key. Exposed so tests can prove the encoding
         * matches the shared SPKI vector without needing a device.
         */
        fun createCertificate(key: StoredKey): X509Certificate =
            createCertificate(key.privateKey, key.publicKey)

        private fun createCertificate(privateKey: PrivateKey, publicKey: PublicKey): X509Certificate {
            val now = System.currentTimeMillis()
            val name = X500Name("CN=$COMMON_NAME")

            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now),
                Date(now - 60_000),
                Date(now + VALIDITY_MILLIS),
                name,
                publicKey,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth))
            )

            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
