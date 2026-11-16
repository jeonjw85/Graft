package kr.jjw.graft.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppEntryTest {
    @Test
    fun `a pinned fingerprint opens the main screen`() {
        assertEquals(StartDestination.Main, AppEntry.startDestination(paired = true))
    }

    @Test
    fun `no pinned fingerprint starts onboarding`() {
        assertEquals(StartDestination.Onboarding, AppEntry.startDestination(paired = false))
    }
}
