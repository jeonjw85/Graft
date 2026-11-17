package kr.jjw.graft.ui

/** Where the app opens: preparation, or the link itself. */
enum class StartDestination {
    Onboarding,
    Main,
}

/**
 * Picks the first screen from the one signal that outlives a process: a pinned fingerprint.
 *
 * Both pairing paths write it, so a user who paired with a code is not sent back through onboarding
 * on the next launch.
 */
object AppEntry {
    fun startDestination(paired: Boolean): StartDestination =
        if (paired) StartDestination.Main else StartDestination.Onboarding

    /**
     * Whether the connection service should start at launch.
     *
     * A saved manual offer (prefix + token) counts: the app can be killed between typing the code
     * and the handshake finishing, and reopening it then used to do nothing at all.
     */
    fun shouldConnect(
        paired: Boolean,
        prefix: String?,
        token: String?,
        userStopped: Boolean = false,
    ): Boolean {
        // Disconnect is a decision, not an accident: reopening the app must not undo it.
        if (userStopped) return false
        return paired || (!prefix.isNullOrEmpty() && !token.isNullOrEmpty())
    }
}
