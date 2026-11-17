package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionGuidanceTest {
    @Test
    fun `an ungranted camera that can still ask is askable`() {
        assertEquals(
            CameraPermissionState.Askable,
            PermissionGuidance.cameraState(granted = false, asked = false, canAskAgain = true)
        )
    }

    @Test
    fun `a denial that can no longer ask is blocked`() {
        assertEquals(
            CameraPermissionState.Blocked,
            PermissionGuidance.cameraState(granted = false, asked = true, canAskAgain = false)
        )
    }

    @Test
    fun `a granted camera needs no prompt`() {
        assertEquals(
            CameraPermissionState.Granted,
            PermissionGuidance.cameraState(granted = true, asked = true, canAskAgain = false)
        )
    }

    @Test
    fun `battery settings are only suggested while the app is optimized`() {
        assertTrue(PermissionGuidance.shouldSuggestBatterySettings(exempt = false, paired = true))
        assertFalse(PermissionGuidance.shouldSuggestBatterySettings(exempt = true, paired = true))
        assertFalse(PermissionGuidance.shouldSuggestBatterySettings(exempt = false, paired = false))
    }

    @Test
    fun `notification settings are only suggested when the channel is hidden`() {
        assertTrue(PermissionGuidance.shouldSuggestNotificationSettings(granted = false, paired = true))
        assertFalse(PermissionGuidance.shouldSuggestNotificationSettings(granted = true, paired = true))
        assertFalse(PermissionGuidance.shouldSuggestNotificationSettings(granted = false, paired = false))
    }
}
