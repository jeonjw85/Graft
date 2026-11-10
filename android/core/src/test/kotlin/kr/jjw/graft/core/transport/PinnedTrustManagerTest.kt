package kr.jjw.graft.core.transport

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinnedTrustManagerTest {
    @Test
    fun `accepts the pinned fingerprint and reports it`() {
        val certificate = HeldCertificate.Builder().commonName("graft").build().certificate
        val pinned = PinnedTrustManager.fingerprintOf(certificate)
        val manager = PinnedTrustManager { setOf(pinned) }
        var reported: String? = null
        manager.onServerVerified = { reported = it }

        manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")

        assertEquals(pinned, reported)
        assertFalse(manager.lastRejectionWasPin)
    }

    @Test
    fun `rejects an unknown fingerprint and marks it as a pin failure`() {
        val certificate = HeldCertificate.Builder().commonName("stranger").build().certificate
        val manager = PinnedTrustManager { emptySet() }

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        }
        assertTrue(manager.lastRejectionWasPin)
    }

    @Test
    fun `rejection flag clears after a successful check`() {
        val certificate = HeldCertificate.Builder().commonName("graft").build().certificate
        val pinned = PinnedTrustManager.fingerprintOf(certificate)
        var allowed = false
        val manager = PinnedTrustManager { if (allowed) setOf(pinned) else emptySet() }

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        }
        assertTrue(manager.lastRejectionWasPin)

        allowed = true
        manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        assertFalse(manager.lastRejectionWasPin)
    }

    @Test
    fun `rejects a missing chain`() {
        val manager = PinnedTrustManager { setOf("anything") }

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(null, "ECDHE_ECDSA")
        }
        assertTrue(manager.lastRejectionWasPin)
    }

    @Test
    fun `begin attempt clears a stale rejection`() {
        val certificate = HeldCertificate.Builder().commonName("stranger").build().certificate
        val manager = PinnedTrustManager { emptySet() }

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate), "ECDHE_ECDSA")
        }
        assertTrue(manager.lastRejectionWasPin)

        manager.beginAttempt()
        assertFalse(manager.lastRejectionWasPin)
    }

    @Test
    fun `checks client chains too`() {
        val certificate = HeldCertificate.Builder().commonName("phone").build().certificate
        val manager = PinnedTrustManager { emptySet() }

        assertThrows(CertificateException::class.java) {
            manager.checkClientTrusted(arrayOf<X509Certificate>(certificate), "ECDHE_ECDSA")
        }
    }
}
