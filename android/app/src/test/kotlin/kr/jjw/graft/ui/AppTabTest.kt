package kr.jjw.graft.ui

import kr.jjw.graft.ui.main.AppTab
import kr.jjw.graft.ui.main.AppTabRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppTabTest {
    @Test
    fun `back from another tab returns to connection`() {
        assertEquals(AppTab.Connection, AppTabRules.onBack(AppTab.Files))
        assertEquals(AppTab.Connection, AppTabRules.onBack(AppTab.Alerts))
    }

    @Test
    fun `back on connection leaves the app`() {
        assertNull(AppTabRules.onBack(AppTab.Connection))
    }

    @Test
    fun `every tab has a label`() {
        assertTrue(AppTab.entries.all { it.label.isNotBlank() })
    }
}
