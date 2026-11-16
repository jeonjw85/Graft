package kr.jjw.graft.core.transport

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kr.jjw.graft.core.SpkiFingerprint

/**
 * Accepts a server certificate only when its SPKI fingerprint starts with an expected eight
 * characters.
 *
 * Manual pairing has no QR code, so the user types a short prefix shown on the Mac. Eight base64url
 * characters is 48 bits: a forged certificate would need a colliding prefix, which is infeasible on
 * a LAN. The full fingerprint is reported after a match so the app can pin it and never rely on a
 * prefix again.
 */
class PrefixTrustManager(expectedPrefix: String) : PinningTrustManager {
    private val prefix: String = expectedPrefix

    /** The full fingerprint from the last accepted certificate, or null after a rejection. */
    @Volatile
    var verifiedFingerprint: String? = null
        private set

    @Volatile
    override var onServerVerified: ((String) -> Unit)? = null

    /** Set when this manager rejected a chain, so callers can distinguish it from a network error. */
    @Volatile
    override var lastRejectionWasPin: Boolean = false
        private set

    override fun beginAttempt() {
        verifiedFingerprint = null
        lastRejectionWasPin = false
    }

    init {
        require(isValidPrefix(prefix)) { "prefix must be 8 base64url characters" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // The phone is the client; there is no client certificate to judge here.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verifiedFingerprint = null
        lastRejectionWasPin = false

        val leaf = chain?.firstOrNull()
        if (leaf == null) {
            lastRejectionWasPin = true
            throw CertificateException("no server certificate")
        }

        val fingerprint = PinnedTrustManager.fingerprintOf(leaf)
        if (!fingerprint.startsWith(prefix)) {
            lastRejectionWasPin = true
            throw CertificateException("fingerprint prefix does not match")
        }

        verifiedFingerprint = fingerprint
        onServerVerified?.invoke(fingerprint)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val PREFIX_LENGTH = 8

        fun isValidPrefix(value: String): Boolean =
            value.length == PREFIX_LENGTH &&
                value.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }
    }
}
