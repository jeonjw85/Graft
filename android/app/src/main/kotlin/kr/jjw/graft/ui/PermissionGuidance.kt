package kr.jjw.graft.ui

/** What the UI should offer for a permission the feature depends on. */
enum class CameraPermissionState {
    /** Nothing to ask. */
    Granted,

    /** The system dialog can still be shown. */
    Askable,

    /** The user denied and the system will no longer prompt: only Settings can change it. */
    Blocked,
}

/**
 * Decides when a missing permission needs an explanation and a shortcut to system settings.
 *
 * None of these permissions block the app: pairing falls back to typed values, alerts and
 * background reliability are optional. The prompt exists so a feature that silently does nothing
 * becomes a visible, fixable choice.
 */
object PermissionGuidance {
    fun cameraState(granted: Boolean, asked: Boolean, canAskAgain: Boolean): CameraPermissionState = when {
        granted -> CameraPermissionState.Granted
        !asked || canAskAgain -> CameraPermissionState.Askable
        else -> CameraPermissionState.Blocked
    }

    /** Battery optimisation can kill the background link, so it is worth one line — but only paired. */
    fun shouldSuggestBatterySettings(exempt: Boolean, paired: Boolean): Boolean = paired && !exempt

    /** The foreground notification is how a user sees that the link is alive. */
    fun shouldSuggestNotificationSettings(granted: Boolean, paired: Boolean): Boolean = paired && !granted
}
