package kr.jjw.graft.core.transport

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kr.jjw.graft.core.SpkiFingerprint

/**
 * Trust manager that accepts exactly the SPKI fingerprints in [allowedFingerprints].
 *
 * The set is read on every check so a new pairing takes effect without rebuilding the client.
 * Both server and client chains are checked: accepting any client certificate would make the
 * pinning pointless.
 */
class PinnedTrustManager(
    private val allowedFingerprints: () -> Set<String>,
) : X509TrustManager {
    /** Invoked with the verified server fingerprint so callers can report the authenticated peer. */
    @Volatile
    var onServerVerified: ((String) -> Unit)? = null

    /** Set when this manager itself rejected a chain, so callers can tell pin failures apart. */
    @Volatile
    var lastRejectionWasPin: Boolean = false

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            requirePinned(chain, "client")
        } catch (error: IllegalArgumentException) {
            lastRejectionWasPin = true
            throw CertificateException(error.message)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            requirePinned(chain, "server")
        } catch (error: IllegalArgumentException) {
            lastRejectionWasPin = true
            throw java.security.cert.CertificateException(error.message)
        }
        lastRejectionWasPin = false
        chain?.firstOrNull()?.let { onServerVerified?.invoke(fingerprintOf(it)) }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun requirePinned(chain: Array<out X509Certificate>?, role: String) {
        requireNotNull(chain) { "no $role certificate chain" }
        require(chain.isNotEmpty()) { "empty $role certificate chain" }
        val fingerprint = fingerprintOf(chain[0])
        require(allowedFingerprints().contains(fingerprint)) {
            "$role fingerprint is not pinned: $fingerprint"
        }
    }

    companion object {
        fun fingerprintOf(certificate: X509Certificate): String =
            SpkiFingerprint.base64Url(encodeSubjectPublicKeyInfo(certificate))

        /**
         * Certificates expose the SPKI DER directly; use it rather than re-encoding the key so the
         * fingerprint matches what Swift and OpenSSL compute.
         */
        private fun encodeSubjectPublicKeyInfo(certificate: X509Certificate): ByteArray =
            certificate.publicKey.encoded

        /** Byte comparison used by tests and diagnostics. */
        fun digestsMatch(left: X509Certificate, right: X509Certificate): Boolean =
            MessageDigest.isEqual(
                left.publicKey.encoded,
                right.publicKey.encoded,
            )
    }
}
