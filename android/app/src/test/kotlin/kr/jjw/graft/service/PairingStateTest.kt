package kr.jjw.graft.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingStateTest {
    private val mac = "fp-mac"

    @Test
    fun `a stored true means the handshake can say hello`() {
        assertTrue(PairingState.hasCompletedPairing(true, mac, setOf(mac)))
    }

    @Test
    fun `a scanned QR fingerprint alone is not a completed pairing`() {
        // The QR path saves the fingerprint with the flag explicitly cleared.
        assertFalse(PairingState.hasCompletedPairing(false, mac, setOf(mac)))
    }

    @Test
    fun `a build without the flag is migrated when the peer is already known`() {
        assertTrue(PairingState.hasCompletedPairing(null, mac, setOf(mac)))
    }

    @Test
    fun `a build without the flag and an unknown peer asks to pair`() {
        assertFalse(PairingState.hasCompletedPairing(null, mac, emptySet()))
    }

    @Test
    fun `no fingerprint never counts as paired`() {
        assertFalse(PairingState.hasCompletedPairing(null, null, setOf(mac)))
        assertFalse(PairingState.hasCompletedPairing(null, "", setOf(mac)))
        assertFalse(PairingState.hasCompletedPairing(false, null, setOf(mac)))
    }
}
