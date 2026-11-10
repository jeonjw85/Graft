package kr.jjw.graft.ui

/** Steps a new user walks through before the app can connect. */
enum class OnboardingStep {
    Welcome,
    Permissions,
    Scan,
    Done,
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val notificationAccessGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val useManualCode: Boolean = false,
    val error: String? = null,
)

/**
 * Onboarding state holder with no Android dependencies, so it runs as a JVM unit test.
 *
 * The permission names are the strings the Activity passes in; keeping them as data instead of
 * Android constants is what makes this testable.
 */
class OnboardingViewModel {
    var state: OnboardingState = OnboardingState()
        private set

    fun advance() {
        state = when (state.step) {
            OnboardingStep.Welcome -> state.copy(step = OnboardingStep.Permissions, error = null)
            OnboardingStep.Permissions ->
                if (state.notificationAccessGranted) {
                    state.copy(step = OnboardingStep.Scan, error = null)
                } else {
                    state.copy(
                        error = "Notification access is required to show phone alerts on your Mac"
                    )
                }
            OnboardingStep.Scan -> state.copy(step = OnboardingStep.Done, error = null)
            OnboardingStep.Done -> state
        }
    }

    fun onPermissionResult(name: String, granted: Boolean) {
        state = when (name) {
            "notifications" -> state.copy(
                notificationAccessGranted = granted,
                error = if (granted) null else {
                    "Notification access is required to show phone alerts on your Mac"
                },
            )
            "camera" -> state.copy(
                cameraGranted = granted,
                useManualCode = !granted,
                error = null,
            )
            else -> state
        }
    }

    fun onCodeEntered(code: String) {
        state = if (code.length == 6 && code.all { it.isDigit() }) {
            state.copy(error = null)
        } else {
            state.copy(error = "Enter the six-digit code shown on your Mac")
        }
    }
}
