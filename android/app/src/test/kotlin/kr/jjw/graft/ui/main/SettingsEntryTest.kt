package kr.jjw.graft.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsEntryTest {
    @Test
    fun `the connection tab keeps only link actions`() {
        assertEquals(
            listOf("Pair another phone", "Reconnect", "Disconnect", "Forget this Mac"),
            SettingsEntry.connectionActions(paired = true, connected = false),
        )
    }

    @Test
    fun `reconnect is hidden while connected`() {
        val actions = SettingsEntry.connectionActions(paired = true, connected = true)

        assertTrue("Reconnect" !in actions, actions.toString())
        assertTrue("Disconnect" in actions)
    }

    @Test
    fun `settings sections cover the moved items`() {
        assertEquals(
            listOf("Background", "Clipboard", "Notifications", "About"),
            SettingsEntry.sections,
        )
    }
}
