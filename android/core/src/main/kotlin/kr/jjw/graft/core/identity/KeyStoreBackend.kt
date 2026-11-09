package kr.jjw.graft.core.identity

import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate

/** A private key handle plus the matching public key. */
data class StoredKey(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
)

/**
 * Storage boundary for the device identity.
 *
 * The Android implementation wraps the Android Keystore, where the private key is non-extractable;
 * the JVM implementation keeps everything in memory for tests. Nothing above this interface reads
 * private key bytes.
 */
interface KeyStoreBackend {
    /** Returns the existing key for [alias] or creates a non-extractable P-256 key. */
    fun loadOrCreateKey(alias: String): StoredKey

    /** Stores the self-signed certificate issued for the key at [alias]. */
    fun storeCertificate(alias: String, der: ByteArray)

    /** Returns the stored certificate, if any. */
    fun loadCertificate(alias: String): X509Certificate?
}
