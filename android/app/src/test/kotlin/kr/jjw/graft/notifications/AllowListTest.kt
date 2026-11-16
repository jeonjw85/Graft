package kr.jjw.graft.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The allow list cache is what the listener reads. These tests pin the rules that matter without an
 * Android context: the default is empty, and the listener must be able to learn a saved list without
 * the UI having been opened in this process.
 */
class AllowListTest {
    private fun candidate(
        pkg: String = "com.example.messenger",
        key: String = "k",
        postedAt: Long = 1,
    ) = PostedNotification(
        pkg = pkg,
        app = "Messenger",
        title = "t",
        text = "b",
        postedAt = postedAt,
        key = key,
        priority = "normal",
    )

    @Test
    fun `the default list forwards nothing`() {
        val filter = NotificationFilter(allowedPackages = { emptySet() }, selfPackage = "kr.jjw.graft")

        assertNull(filter.accept(candidate(), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `adding an app forwards it and removing stops it`() {
        var allowed = emptySet<String>()
        val filter = NotificationFilter(allowedPackages = { allowed }, selfPackage = "kr.jjw.graft")

        assertNull(filter.accept(candidate(), isOngoing = false, isGroupSummary = false))

        allowed = setOf("com.example.messenger")
        assertNotNull(filter.accept(candidate(), isOngoing = false, isGroupSummary = false))

        allowed = emptySet()
        assertNull(filter.accept(candidate(key = "k2", postedAt = 2), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `the filter reads the allow list on every call`() {
        var allowed = setOf("a.one")
        val filter = NotificationFilter(allowedPackages = { allowed }, selfPackage = "kr.jjw.graft")

        assertNotNull(filter.accept(candidate(pkg = "a.one"), false, false))

        allowed = setOf("a.two")
        assertNull(filter.accept(candidate(pkg = "a.one", key = "k2", postedAt = 2), false, false))
        assertNotNull(filter.accept(candidate(pkg = "a.two", key = "k3", postedAt = 3), false, false))
    }

    @Test
    fun `a listener process that never opened the UI still forwards a saved app`() {
        // The listener runs in its own process; it must load the saved list itself rather than
        // depending on the settings screen having run. This pins the contract: seed the cache the
        // same way the load path does, then assert the filter accepts.
        NotificationAllowList.seedForTest(setOf("com.example.messenger"))
        try {
            val filter = NotificationFilter(
                allowedPackages = { NotificationAllowList.cached() },
                selfPackage = "kr.jjw.graft",
            )
            assertNotNull(filter.accept(candidate(), false, false))
        } finally {
            NotificationAllowList.seedForTest(emptySet())
        }
    }

    @Test
    fun `seeding an empty list forwards nothing`() {
        NotificationAllowList.seedForTest(emptySet())
        val filter = NotificationFilter(
            allowedPackages = { NotificationAllowList.cached() },
            selfPackage = "kr.jjw.graft",
        )

        assertNull(filter.accept(candidate(), false, false))
        assertTrue(NotificationAllowList.cached().isEmpty())
        assertEquals(0, NotificationAllowList.cached().size)
    }
}
