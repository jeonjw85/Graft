package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppEntryTest {
    @Test
    fun `a pinned fingerprint opens the main screen`() {
        assertEquals(StartDestination.Main, AppEntry.startDestination(paired = true))
    }

    @Test
    fun `a saved manual offer resumes connecting`() {
        assertTrue(AppEntry.shouldConnect(paired = false, prefix = "Am_YijwV", token = "042817"))
    }

    @Test
    fun `a half-saved offer does not connect`() {
        assertFalse(AppEntry.shouldConnect(paired = false, prefix = "Am_YijwV", token = null))
        assertFalse(AppEntry.shouldConnect(paired = false, prefix = null, token = "042817"))
    }

    @Test
    fun `a paired app always connects`() {
        assertTrue(AppEntry.shouldConnect(paired = true, prefix = null, token = null))
    }

    @Test
    fun `no pinned fingerprint starts onboarding`() {
        assertEquals(StartDestination.Onboarding, AppEntry.startDestination(paired = false))
    }
}
