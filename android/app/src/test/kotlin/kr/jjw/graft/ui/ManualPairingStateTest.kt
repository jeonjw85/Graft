package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualPairingStateTest {
    private fun validate(
        code: String = "042817",
        prefix: String = "Am_YijwV",
        host: String = "192.168.0.12",
        port: String = "47800",
    ) = ManualPairingRules.validate(code, prefix, host, port)

    @Test
    fun `valid input produces a manual pairing`() {
        val result = validate()

        assertTrue(result is ManualPairingRules.Result.Valid)
        val pairing = (result as ManualPairingRules.Result.Valid).pairing
        assertEquals("042817", pairing.code)
        assertEquals("Am_YijwV", pairing.prefix)
        assertEquals("192.168.0.12", pairing.host)
        assertEquals(47800, pairing.port)
    }

    @Test
    fun `a five digit code is refused`() {
        val result = validate(code = "04281")
        assertEquals(
            "Enter the six-digit code shown on your Mac",
            (result as ManualPairingRules.Result.Invalid).message
        )
    }

    @Test
    fun `a seven digit code is refused`() {
        assertTrue(validate(code = "0428171") is ManualPairingRules.Result.Invalid)
    }

    @Test
    fun `a non-numeric code is refused`() {
        assertTrue(validate(code = "04a817") is ManualPairingRules.Result.Invalid)
    }

    @Test
    fun `a seven character prefix is refused`() {
        val result = validate(prefix = "Am_Yijw")
        assertEquals(
            "Enter the eight-character fingerprint shown on your Mac",
            (result as ManualPairingRules.Result.Invalid).message
        )
    }

    @Test
    fun `a prefix with padding or plus is refused`() {
        assertTrue(validate(prefix = "Am+YijwV") is ManualPairingRules.Result.Invalid)
        assertTrue(validate(prefix = "Am/YijwV") is ManualPairingRules.Result.Invalid)
        assertTrue(validate(prefix = "Am_Yijw=") is ManualPairingRules.Result.Invalid)
    }

    @Test
    fun `an empty host is refused`() {
        val result = validate(host = "   ")
        assertEquals(
            "Enter your Mac's IP address",
            (result as ManualPairingRules.Result.Invalid).message
        )
    }

    @Test
    fun `an invalid port falls back to the default`() {
        val result = validate(port = "abc")
        assertEquals(47800, (result as ManualPairingRules.Result.Valid).pairing.port)
    }

    @Test
    fun `an out of range port falls back to the default`() {
        assertEquals(47800, (validate(port = "70000") as ManualPairingRules.Result.Valid).pairing.port)
        assertEquals(47800, (validate(port = "0") as ManualPairingRules.Result.Valid).pairing.port)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val result = validate(code = " 042817 ", prefix = " Am_YijwV ", host = " 10.0.0.5 ")
        val pairing = (result as ManualPairingRules.Result.Valid).pairing
        assertEquals("042817", pairing.code)
        assertEquals("Am_YijwV", pairing.prefix)
        assertEquals("10.0.0.5", pairing.host)
    }
}
