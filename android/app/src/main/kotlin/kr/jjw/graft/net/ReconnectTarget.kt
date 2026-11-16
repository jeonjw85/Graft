package kr.jjw.graft.net

/**
 * Picks the Mac to reconnect to among discovered hosts.
 *
 * Only a host whose advertised fingerprint is the one this phone trusts may win: discovery tells us
 * where a machine is, never that it is the right machine. Before the first successful pairing the
 * full fingerprint is not known yet, so the eight characters the user typed are used instead —
 * the same 48 bits the pairing handshake checks.
 */
object ReconnectTarget {
    fun match(
        pinnedFingerprint: String?,
        prefix: String?,
        candidates: List<DiscoveredHost>,
    ): DiscoveredHost? {
        val pinned = pinnedFingerprint?.takeIf { it.isNotEmpty() }
        val short = prefix?.takeIf { it.isNotEmpty() }

        return candidates.firstOrNull { candidate ->
            if (candidate.fingerprint.isEmpty()) return@firstOrNull false
            when {
                pinned != null -> candidate.fingerprint == pinned
                short != null -> candidate.fingerprint.startsWith(short)
                else -> false
            }
        }
    }
}
