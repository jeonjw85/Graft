package kr.jjw.graft.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReconnectTargetTest {
    private fun host(
        host: String = "192.168.0.5",
        port: Int = 47800,
        fingerprint: String = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ",
    ) = DiscoveredHost(host, port, fingerprint)

    private val pinned = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"

    @Test
    fun `a matching fingerprint is chosen even when another mac is nearby`() {
        val chosen = ReconnectTarget.match(
            pinnedFingerprint = pinned,
            prefix = null,
            candidates = listOf(host(host = "192.168.0.9", fingerprint = "ZZZZZZZZzzzzzz"), host()),
        )

        assertEquals("192.168.0.5", chosen?.host)
    }

    @Test
    fun `a different fingerprint is never chosen`() {
        assertNull(
            ReconnectTarget.match(
                pinnedFingerprint = pinned,
                prefix = null,
                candidates = listOf(host(fingerprint = "ZZZZZZZZ")),
            )
        )
    }

    @Test
    fun `a manual pairing matches on the typed prefix`() {
        val chosen = ReconnectTarget.match(
            pinnedFingerprint = null,
            prefix = "Am_YijwV",
            candidates = listOf(host(host = "192.168.0.7")),
        )

        assertEquals("192.168.0.7", chosen?.host)
    }

    @Test
    fun `a candidate without a fingerprint is ignored`() {
        assertNull(
            ReconnectTarget.match(
                pinnedFingerprint = null,
                prefix = "Am_YijwV",
                candidates = listOf(host(fingerprint = "")),
            )
        )
    }

    @Test
    fun `knowing neither the pin nor the prefix finds nothing`() {
        assertNull(ReconnectTarget.match(pinnedFingerprint = null, prefix = null, candidates = listOf(host())))
    }
}
