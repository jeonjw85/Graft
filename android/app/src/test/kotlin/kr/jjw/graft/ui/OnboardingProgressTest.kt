package kr.jjw.graft.ui.onboarding

import kr.jjw.graft.ui.OnboardingStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnboardingProgressTest {
    @Test
    fun `steps are numbered`() {
        assertEquals("1 of 3", OnboardingProgress.label(OnboardingStep.Welcome))
        assertEquals("2 of 3", OnboardingProgress.label(OnboardingStep.Permissions))
        assertEquals("3 of 3", OnboardingProgress.label(OnboardingStep.Scan))
    }

    @Test
    fun `the finished step has no counter`() {
        assertEquals("", OnboardingProgress.label(OnboardingStep.Done))
    }
}
