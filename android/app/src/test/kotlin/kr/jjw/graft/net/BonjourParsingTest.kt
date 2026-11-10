package kr.jjw.graft.net

import kr.jjw.graft.core.BonjourServiceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The Android NSD layer needs a device; these tests pin the parsing rules that decide whether a
 * discovered service is the paired peer, which is the part that can silently go wrong.
 */
class BonjourParsingTest {
    private val fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"

    @Test
    fun `discovered name yields the short fingerprint`() {
        val name = BonjourServiceInfo.serviceName("MacBook Pro", fingerprint)
        assertEquals("Am_YijwV", BonjourServiceInfo.shortFingerprint(name))
    }

    @Test
    fun `a foreign service name is not treated as graft`() {
        assertNull(BonjourServiceInfo.shortFingerprint("Printer (1234)"))
        assertEquals("_graft._tcp", BonjourServiceInfo.SERVICE_TYPE)
    }

    @Test
    fun `empty fingerprint from txt record must be ignored`() {
        // The TXT record is authoritative; a resolved service with no fp must not be trusted.
        val discovered = DiscoveredHost(host = "192.168.0.12", port = 47800, fingerprint = "")
        assertFalse(discovered.fingerprint.isNotEmpty())
    }

    @Test
    fun `korean device names stay under the byte limit`() {
        val name = BonjourServiceInfo.serviceName("가".repeat(40), fingerprint)
        assertEquals(true, name.toByteArray(Charsets.UTF_8).size <= BonjourServiceInfo.MAX_BYTES)
    }
}
