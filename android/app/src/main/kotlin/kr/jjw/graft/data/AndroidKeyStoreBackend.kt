package kr.jjw.graft.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kr.jjw.graft.core.identity.KeyStoreBackend
import kr.jjw.graft.core.identity.StoredKey

/**
 * Android Keystore implementation of [KeyStoreBackend].
 *
 * The private key is generated inside the Keystore and cannot be extracted; only a signing handle
 * leaves it. The Keystore cannot store certificates, so the self-signed certificate DER lives in
 * SharedPreferences and is re-attached to the key handle on load.
 */
class AndroidKeyStoreBackend(context: Context) : KeyStoreBackend {
    private val preferences =
        context.applicationContext.getSharedPreferences("graft-identity", Context.MODE_PRIVATE)

    override fun loadOrCreateKey(alias: String): StoredKey {
        val keyStore = keyStore()

        // Keys made before identity version 4 did not authorize DIGEST_NONE, which is the raw
        // ECDSA signature Conscrypt uses for the TLS client CertificateVerify ("Incompatible
        // digest"). Digest authorization cannot be changed after generation, so such a key is
        // replaced once. v0.1 has no released builds; a paired phone must pair again, which is why
        // this happens silently rather than as a migration dialog.
        if (keyStore.containsAlias(alias) && preferences.getInt(IDENTITY_VERSION_KEY, 1) < 4) {
            runCatching { keyStore.deleteEntry(alias) }
            preferences.edit().remove(certificateKey(alias)).apply()
        }

        if (!keyStore.containsAlias(alias)) {
            generateKey(alias)
            preferences.edit().putInt(IDENTITY_VERSION_KEY, 4).apply()
        }

        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Keystore entry $alias is not a private key")
        return StoredKey(entry.privateKey as PrivateKey, entry.certificate.publicKey as PublicKey)
    }

    override fun storeCertificate(alias: String, der: ByteArray) {
        preferences.edit()
            .putString(certificateKey(alias), Base64.encodeToString(der, Base64.NO_WRAP))
            .apply()
    }

    override fun loadCertificate(alias: String): X509Certificate? {
        val encoded = preferences.getString(certificateKey(alias), null) ?: return null
        val der = Base64.decode(encoded, Base64.NO_WRAP)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun generateKey(alias: String) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt signs the already-hashed CertificateVerify with NONEwithECDSA, so the
                // key must authorize DIGEST_NONE; the hashed variants keep software callers working.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun certificateKey(alias: String) = "certificate-$alias"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val IDENTITY_VERSION_KEY = "identity-version"
    }

}
