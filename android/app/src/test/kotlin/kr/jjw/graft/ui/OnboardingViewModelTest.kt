package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingViewModelTest {
    private val viewModel = OnboardingViewModel()

    @Test
    fun `starts at welcome`() {
        assertEquals(OnboardingStep.Welcome, viewModel.state.step)
    }

    @Test
    fun `welcome advances to permissions`() {
        viewModel.advance()
        assertEquals(OnboardingStep.Permissions, viewModel.state.step)
    }

    @Test
    fun `blocked notification access prevents proceeding`() {
        viewModel.advance()
        viewModel.onPermissionResult("notifications", false)

        viewModel.advance()

        assertEquals(OnboardingStep.Permissions, viewModel.state.step)
        assertEquals(
            "Notification access is required to show phone alerts on your Mac",
            viewModel.state.error,
        )
    }

    @Test
    fun `granted notification access allows scan step`() {
        viewModel.advance()
        viewModel.onPermissionResult("notifications", true)

        viewModel.advance()

        assertEquals(OnboardingStep.Scan, viewModel.state.step)
        assertEquals(null, viewModel.state.error)
    }

    @Test
    fun `camera denial falls back to the manual code`() {
        viewModel.onPermissionResult("camera", false)

        assertEquals(OnboardingStep.Welcome, viewModel.state.step)
        assertTrue(viewModel.state.useManualCode)
        assertFalse(viewModel.state.cameraGranted)
    }

    @Test
    fun `camera grant clears manual fallback`() {
        viewModel.onPermissionResult("camera", false)
        viewModel.onPermissionResult("camera", true)

        assertFalse(viewModel.state.useManualCode)
        assertTrue(viewModel.state.cameraGranted)
    }

    @Test
    fun `unknown permission name is ignored`() {
        val before = viewModel.state
        viewModel.onPermissionResult("location", true)
        assertEquals(before, viewModel.state)
    }

    @Test
    fun `valid six-digit code is accepted`() {
        viewModel.onCodeEntered("042817")
        assertEquals(null, viewModel.state.error)
    }

    @Test
    fun `short or non-numeric code is rejected with guidance`() {
        viewModel.onCodeEntered("04281")
        assertEquals("Enter the six-digit code shown on your Mac", viewModel.state.error)

        viewModel.onCodeEntered("04a817")
        assertEquals("Enter the six-digit code shown on your Mac", viewModel.state.error)
    }
}
