package kr.jjw.graft.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The allow list is persisted through DataStore on device; these tests pin the rules the UI and the
 * filter share, without needing an Android context.
 */
class AllowListTest {
    @Test
    fun `the default list forwards nothing`() {
        val filter = NotificationFilter(allowedPackages = { emptySet() }, selfPackage = "kr.jjw.graft")
        val candidate = PostedNotification(
            pkg = "com.example.messenger",
            app = "Messenger",
            title = "t",
            text = "b",
            postedAt = 1,
            key = "k",
            priority = "normal",
        )

        assertEquals(null, filter.accept(candidate, isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `adding an app forwards it and removing stops it`() {
        var allowed = emptySet<String>()
        val filter = NotificationFilter(allowedPackages = { allowed }, selfPackage = "kr.jjw.graft")
        val candidate = PostedNotification(
            pkg = "com.example.messenger",
            app = "Messenger",
            title = "t",
            text = "b",
            postedAt = 1,
            key = "k",
            priority = "normal",
        )

        assertFalse(filter.accept(candidate, isOngoing = false, isGroupSummary = false) != null)

        allowed = setOf("com.example.messenger")
        assertTrue(filter.accept(candidate, isOngoing = false, isGroupSummary = false) != null)

        allowed = emptySet()
        assertFalse(
            filter.accept(candidate.copy(postedAt = 2, key = "k2"), isOngoing = false, isGroupSummary = false) != null
        )
    }

    @Test
    fun `the filter reads the allow list on every call`() {
        var allowed = setOf("a.one")
        val filter = NotificationFilter(allowedPackages = { allowed }, selfPackage = "kr.jjw.graft")
        val candidate = PostedNotification("a.one", "One", "t", "b", 1, "k", "normal")

        assertTrue(filter.accept(candidate, false, false) != null)

        allowed = setOf("a.two")
        assertNullForDifferentApp(filter, candidate)
    }

    private fun assertNullForDifferentApp(filter: NotificationFilter, candidate: PostedNotification) {
        assertEquals(
            null,
            filter.accept(candidate.copy(key = "k2", postedAt = 2), false, false)
        )
    }
}
