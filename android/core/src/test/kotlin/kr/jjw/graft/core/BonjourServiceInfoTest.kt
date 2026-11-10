package kr.jjw.graft.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BonjourServiceInfoTest {
    private val fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"

    @Test
    fun `service name carries the short fingerprint`() {
        assertEquals(
            "MacBook Pro (Am_YijwV)",
            BonjourServiceInfo.serviceName("MacBook Pro", fingerprint)
        )
    }

    @Test
    fun `service name changes when the fingerprint changes`() {
        val first = BonjourServiceInfo.serviceName("Mac", "AAAAAAAA1111")
        val second = BonjourServiceInfo.serviceName("Mac", "BBBBBBBB2222")
        assertTrue(first != second)
    }

    @Test
    fun `short fingerprint round trips`() {
        val name = BonjourServiceInfo.serviceName("Mac", fingerprint)
        assertEquals("Am_YijwV", BonjourServiceInfo.shortFingerprint(name))
    }

    @Test
    fun `name without parentheses has no short fingerprint`() {
        assertNull(BonjourServiceInfo.shortFingerprint("MacBook Pro"))
    }

    @Test
    fun `korean name is truncated on a character boundary`() {
        val long = "가".repeat(40) // 120 bytes in UTF-8

        val truncated = BonjourServiceInfo.serviceName(long, fingerprint)

        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= BonjourServiceInfo.MAX_BYTES)
        // No replacement characters: the truncation must not split a 3-byte code point.
        assertTrue(!truncated.contains('\uFFFD'))
    }

    @Test
    fun `ascii name under the limit is untouched apart from the suffix`() {
        assertEquals(
            "Mac (Am_YijwV)",
            BonjourServiceInfo.serviceName("Mac", fingerprint)
        )
    }
}
