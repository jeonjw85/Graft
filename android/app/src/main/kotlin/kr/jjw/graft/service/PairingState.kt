package kr.jjw.graft.service

/**
 * Decides whether the phone should say `hello` or ask to pair.
 *
 * A fingerprint saved from a QR code only means "this is the Mac we are about to pair with"; it is
 * written before the Mac has accepted anything. Treating that as "paired" made the first QR
 * connection send `hello`, which the Mac refuses, so QR pairing could never complete.
 */
object PairingState {
    /**
     * @param completed the stored flag, or null on builds that predate it
     * @param pinned the fingerprint currently saved for the next connection
     * @param knownPeers fingerprints the Mac has already accepted
     */
    fun hasCompletedPairing(
        completed: Boolean?,
        pinned: String?,
        knownPeers: Set<String>,
    ): Boolean {
        completed?.let { return it }
        if (pinned.isNullOrEmpty()) return false
        return pinned in knownPeers
    }
}
