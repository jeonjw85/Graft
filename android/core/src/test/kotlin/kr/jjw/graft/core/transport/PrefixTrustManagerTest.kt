package kr.jjw.graft.core.transport

import java.security.cert.CertificateException
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrefixTrustManagerTest {
    private fun certificate(name: String = "graft") =
        HeldCertificate.Builder().commonName(name).build().certificate

    @Test
    fun `accepts a certificate whose fingerprint starts with the prefix`() {
        val cert = certificate()
        val full = PinnedTrustManager.fingerprintOf(cert)
        val manager = PrefixTrustManager(full.take(8))

        manager.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")

        assertEquals(full, manager.verifiedFingerprint)
        assertFalse(manager.lastRejectionWasPrefix)
    }

    @Test
    fun `rejects a certificate with a different prefix`() {
        val cert = certificate()
        val other = certificate("other")
        val otherPrefix = PinnedTrustManager.fingerprintOf(other).take(8)
        val manager = PrefixTrustManager(otherPrefix)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        }
        assertNull(manager.verifiedFingerprint)
        assertTrue(manager.lastRejectionWasPrefix)
    }

    @Test
    fun `rejects a prefix of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("short") }
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("toolongprefix") }
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("") }
    }

    @Test
    fun `rejects a prefix with characters outside base64url`() {
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("Am+YijwV") }
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("Am/YijwV") }
        assertThrows(IllegalArgumentException::class.java) { PrefixTrustManager("Am YijwV") }
    }

    @Test
    fun `reports the full fingerprint for pinning`() {
        val cert = certificate()
        val full = PinnedTrustManager.fingerprintOf(cert)
        val manager = PrefixTrustManager(full.take(8))

        manager.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")

        val verified = manager.verifiedFingerprint
        assertEquals(43, verified?.length)
        assertTrue(verified!!.startsWith(full.take(8)))
        assertEquals(full, verified)
    }

    @Test
    fun `a rejection clears a previous verification`() {
        val accepted = certificate("accepted")
        val rejected = certificate("rejected")
        val manager = PrefixTrustManager(PinnedTrustManager.fingerprintOf(accepted).take(8))

        manager.checkServerTrusted(arrayOf(accepted), "ECDHE_ECDSA")
        assertEquals(43, manager.verifiedFingerprint?.length)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(rejected), "ECDHE_ECDSA")
        }
        assertNull(manager.verifiedFingerprint, "a rejected handshake must not leave a stale pin")
    }

    @Test
    fun `rejects a missing chain`() {
        val manager = PrefixTrustManager("Am_YijwV")

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(null, "ECDHE_ECDSA")
        }
        assertTrue(manager.lastRejectionWasPrefix)
    }

    @Test
    fun `client certificates are not judged`() {
        val manager = PrefixTrustManager("Am_YijwV")
        // The phone is the client; this must not throw even with a mismatching chain.
        manager.checkClientTrusted(arrayOf(certificate()), "ECDHE_ECDSA")
        assertNull(manager.verifiedFingerprint)
    }
}
