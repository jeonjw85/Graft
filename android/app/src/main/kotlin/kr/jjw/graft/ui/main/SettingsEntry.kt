package kr.jjw.graft.ui.main

/**
 * What belongs on the Connection tab.
 *
 * Permissions, clipboard behaviour, and app information are settings, not link actions; keeping them
 * off the main screen is what makes the link state readable at a glance.
 */
object SettingsEntry {
    val sections = listOf("Background", "Clipboard", "Notifications", "About")

    fun connectionActions(paired: Boolean, connected: Boolean): List<String> {
        if (!paired) return emptyList()
        return buildList {
            add("Pair another phone")
            if (!connected) add("Reconnect")
            add("Disconnect")
            add("Forget this Mac")
        }
    }
}
