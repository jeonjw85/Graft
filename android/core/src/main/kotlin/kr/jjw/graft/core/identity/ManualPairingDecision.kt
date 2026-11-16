package kr.jjw.graft.core.identity

/**
 * Decides what to do when a manual pairing verifies a Mac's fingerprint.
 *
 * A manual pairing must never silently replace a different Mac. Replacing an existing pin is
 * allowed only when the user explicitly asked for it (re-pairing), so the app shows a confirmation
 * instead of overwriting.
 */
object ManualPairingDecision {
    sealed interface Result {
        /** No peer stored yet, or the same Mac re-paired: pin this fingerprint. */
        data class Pin(val fingerprint: String) : Result

        /** A different Mac is already paired. The caller must ask before replacing it. */
        data class AlreadyPaired(val existingFingerprint: String) : Result

        /** Verification did not produce a fingerprint, so nothing may be written. */
        data object Unverified : Result

        /** The caller passed an invalid prefix length; refuse before touching state. */
        data class InvalidPrefix(val prefix: String) : Result
    }

    const val PREFIX_LENGTH = 8

    fun decide(existingFingerprint: String?, prefix: String, verifiedFingerprint: String?): Result {
        if (prefix.length != PREFIX_LENGTH) return Result.InvalidPrefix(prefix)
        if (verifiedFingerprint == null || verifiedFingerprint.length != 43) {
            return Result.Unverified
        }
        if (!verifiedFingerprint.startsWith(prefix)) {
            // The handshake must have been verified against this prefix; anything else means the
            // caller mixed up attempts.
            return Result.Unverified
        }

        if (existingFingerprint == null) return Result.Pin(verifiedFingerprint)
        if (existingFingerprint == verifiedFingerprint) return Result.Pin(verifiedFingerprint)
        return Result.AlreadyPaired(existingFingerprint)
    }
}
