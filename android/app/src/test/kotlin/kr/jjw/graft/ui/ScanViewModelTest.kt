package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanViewModelTest {
    private val fingerprint = "Am_YijwVk5n7_tmLe2VdqQtXgr-8oAylln366J659dQ"
    private val validUri =
        "graft://pair?v=1&h=192.168.0.12&p=47800&fp=$fingerprint&t=abcdefghijklmnopqrstuvwx&n=MacBook%20Pro"

    @Test
    fun `valid uri yields an offer`() {
        val viewModel = ScanViewModel()
        viewModel.onQRScanned(validUri)

        val offer = assertNotNull(viewModel.state.offer)
        assertEquals("192.168.0.12", offer.host)
        assertEquals(47800, offer.port)
        assertEquals(fingerprint, offer.fingerprint)
        assertEquals("MacBook Pro", offer.name)
        assertNull(viewModel.state.error)
    }

    @Test
    fun `https url is rejected with a clear message`() {
        val viewModel = ScanViewModel()
        viewModel.onQRScanned("https://example.com/pair")

        assertNull(viewModel.state.offer)
        assertEquals("That is not a Graft pairing code", viewModel.state.error)
    }

    @Test
    fun `empty scan is rejected`() {
        val viewModel = ScanViewModel()
        viewModel.onQRScanned("   ")

        assertEquals("That QR code is empty", viewModel.state.error)
    }

    @Test
    fun `damaged fingerprint is described to the user`() {
        val viewModel = ScanViewModel()
        viewModel.onQRScanned(validUri.replace(fingerprint, "tooshort"))

        assertEquals("The pairing code is damaged", viewModel.state.error)
    }

    @Test
    fun `unsupported version points at an upgrade`() {
        val viewModel = ScanViewModel()
        viewModel.onQRScanned(validUri.replace("v=1", "v=2"))

        assertEquals("This Mac needs a newer version of Graft", viewModel.state.error)
    }

    @Test
    fun `five-digit code is rejected`() {
        val viewModel = ScanViewModel(defaultHost = "192.168.0.12")
        viewModel.onCodeEntered("12345", null)

        assertEquals("Enter the six-digit code shown on your Mac", viewModel.state.error)
        assertTrue(viewModel.state.useManualCode)
    }

    @Test
    fun `six-digit code is refused because it carries no certificate fingerprint`() {
        // TLS pins the Mac by SPKI. A typed code has no fingerprint to pin, so accepting it would
        // either fail silently at the TLS layer or, worse, invite trust-on-first-use. Refuse it
        // with an explanation instead.
        val viewModel = ScanViewModel(defaultHost = "192.168.0.12")
        viewModel.onCodeEntered("042817", null)

        assertNull(viewModel.state.offer)
        assertEquals(
            "The six-digit code needs the camera path for now. Scan the QR code instead.",
            viewModel.state.error
        )
        assertTrue(viewModel.state.useManualCode)
    }

    @Test
    fun `six-digit code without a host reports the missing address first`() {
        val viewModel = ScanViewModel()
        viewModel.onCodeEntered("042817", null)

        assertNull(viewModel.state.offer)
        assertEquals("Enter your Mac's IP address too", viewModel.state.error)
    }

    @Test
    fun `non-numeric six characters are rejected`() {
        val viewModel = ScanViewModel(defaultHost = "192.168.0.12")
        viewModel.onCodeEntered("04a817", null)

        assertEquals("Enter the six-digit code shown on your Mac", viewModel.state.error)
    }
}
