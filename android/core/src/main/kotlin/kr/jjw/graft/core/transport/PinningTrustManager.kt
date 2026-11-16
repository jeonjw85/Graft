package kr.jjw.graft.core.transport

import javax.net.ssl.X509TrustManager

/**
 * What [GraftClient] needs from a trust manager.
 *
 * Both implementations pin the server by fingerprint: [PinnedTrustManager] compares the full value,
 * [PrefixTrustManager] compares an eight-character prefix during manual pairing.
 */
interface PinningTrustManager : X509TrustManager {
    /** Invoked with the verified server fingerprint so callers can report the authenticated peer. */
    var onServerVerified: ((String) -> Unit)?

    /** True when the last rejection came from this manager rather than the network. */
    val lastRejectionWasPin: Boolean

    /** Clears per-attempt state before a new handshake starts. */
    fun beginAttempt()
}
