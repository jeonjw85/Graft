package kr.jjw.graft.ui.main

import kr.jjw.graft.notifications.PostedNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AlertsScreenStateTest {
    private fun posted(index: Int) = PostedNotification(
        pkg = "com.example.$index",
        app = "App $index",
        title = "Title $index",
        text = "Text $index",
        postedAt = index.toLong(),
        key = "key-$index",
        priority = "normal",
    )

    @Test
    fun `recent alerts are limited to five`() {
        val state = AlertsScreenRules.from(accessGranted = true, recent = (1..8).map(::posted))

        assertEquals(5, state.recent.size)
        assertEquals(listOf(1, 2, 3, 4, 5), state.recent.map { it.postedAt.toInt() })
    }

    @Test
    fun `denied access still lists what was forwarded before`() {
        val state = AlertsScreenRules.from(accessGranted = false, recent = listOf(posted(1)))

        assertFalse(state.accessGranted)
        assertEquals(1, state.recent.size)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(0, AlertsScreenRules.from(accessGranted = true, recent = emptyList()).recent.size)
    }
}
