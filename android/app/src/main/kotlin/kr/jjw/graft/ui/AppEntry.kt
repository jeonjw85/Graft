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
}
