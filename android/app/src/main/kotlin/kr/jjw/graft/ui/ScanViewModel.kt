package kr.jjw.graft.ui

import kr.jjw.graft.core.PairingOffer
import kr.jjw.graft.core.PairingUri
import kr.jjw.graft.core.PairingUriException

data class ScanState(
    val offer: PairingOffer? = null,
    val error: String? = null,
    val useManualCode: Boolean = false,
)

/**
 * Turns scanned or typed pairing input into an offer.
 *
 * No Android dependencies, so the validation rules run as JVM tests.
 */
class ScanViewModel(private val defaultHost: String? = null) {
    var state: ScanState = ScanState()
        private set

    fun onQRScanned(raw: String) {
        val trimmed = raw.trim()
        state = when {
            trimmed.isEmpty() -> ScanState(error = "That QR code is empty")
            !trimmed.startsWith("graft://") -> ScanState(error = "That is not a Graft pairing code")
            else -> try {
                ScanState(offer = PairingUri.parse(trimmed))
            } catch (error: PairingUriException) {
                ScanState(error = describe(error))
            }
        }
    }

    fun onCodeEntered(code: String, host: String?) {
        val trimmed = code.trim()
        val targetHost = host?.trim()?.takeIf { it.isNotEmpty() } ?: defaultHost

        state = when {
            trimmed.length != 6 || !trimmed.all { it.isDigit() } ->
                ScanState(error = "Enter the six-digit code shown on your Mac", useManualCode = true)

            targetHost.isNullOrBlank() ->
                ScanState(error = "Enter your Mac's IP address too", useManualCode = true)

            // TLS authenticates the Mac by its SPKI fingerprint. A typed code carries no
            // fingerprint, so accepting it would either fail at the TLS layer with a confusing
            // error or invite trust-on-first-use, which this project explicitly avoids.
            else -> ScanState(
                error = "The six-digit code needs the camera path for now. Scan the QR code instead.",
                useManualCode = true,
            )
        }
    }

    private fun describe(error: PairingUriException): String = when {
        error.message?.contains("fingerprint") == true -> "The pairing code is damaged"
        error.message?.contains("version") == true -> "This Mac needs a newer version of Graft"
        error.message?.contains("port") == true -> "The pairing code has an invalid address"
        error.message?.contains("token") == true -> "The pairing code has an invalid token"
        else -> "That pairing code is not valid"
    }

}
