package kr.jjw.graft.core.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManualPairingDecisionTest {
    private val prefix = "Am_YijwV"
    private val full = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"
    private val otherFull = "ZZZZZZZZk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"

    @Test
    fun `first pairing pins the verified fingerprint`() {
        val result = ManualPairingDecision.decide(null, prefix, full)
        assertEquals(ManualPairingDecision.Result.Pin(full), result)
    }

    @Test
    fun `re-pairing the same mac pins it again`() {
        val result = ManualPairingDecision.decide(full, prefix, full)
        assertTrue(result is ManualPairingDecision.Result.Pin)
    }

    @Test
    fun `a different mac is not silently replaced`() {
        val result = ManualPairingDecision.decide(otherFull, prefix, full)
        assertEquals(ManualPairingDecision.Result.AlreadyPaired(otherFull), result)
    }

    @Test
    fun `an unverified handshake writes nothing`() {
        assertEquals(
            ManualPairingDecision.Result.Unverified,
            ManualPairingDecision.decide(null, prefix, null)
        )
    }

    @Test
    fun `a fingerprint that does not start with the prefix is refused`() {
        assertEquals(
            ManualPairingDecision.Result.Unverified,
            ManualPairingDecision.decide(null, "ZZZZZZZZ", full)
        )
    }

    @Test
    fun `a wrongly sized fingerprint is refused`() {
        assertEquals(
            ManualPairingDecision.Result.Unverified,
            ManualPairingDecision.decide(null, prefix, "Am_YijwV")
        )
    }

    @Test
    fun `a wrongly sized prefix is refused before any state is touched`() {
        assertEquals(
            ManualPairingDecision.Result.InvalidPrefix("Am_Yijw"),
            ManualPairingDecision.decide(null, "Am_Yijw", full)
        )
    }
}
