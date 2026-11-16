package kr.jjw.graft.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationFilterTest {
    private val selfPackage = "kr.jjw.graft"

    private fun candidate(
        pkg: String = "com.example.messenger",
        text: String = "hello",
        key: String = "k1",
        postedAt: Long = 1_762_752_000_000,
    ) = PostedNotification(
        pkg = pkg,
        app = "Messenger",
        title = "New message",
        text = text,
        postedAt = postedAt,
        key = key,
        priority = "normal",
    )

    private fun filter(allowed: Set<String> = setOf("com.example.messenger")) =
        NotificationFilter(allowedPackages = { allowed }, selfPackage = selfPackage)

    @Test
    fun `rejects our own package`() {
        assertNull(filter().accept(candidate(pkg = selfPackage), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `rejects ongoing and group summaries`() {
        val filter = filter()
        assertNull(filter.accept(candidate(), isOngoing = true, isGroupSummary = false))
        assertNull(filter.accept(candidate(key = "k2"), isOngoing = false, isGroupSummary = true))
    }

    @Test
    fun `rejects apps not on the allow list`() {
        val emptyList = NotificationFilter(allowedPackages = { emptySet() }, selfPackage = selfPackage)
        assertNull(emptyList.accept(candidate(), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `accepts an allow-listed normal notification`() {
        val accepted = filter().accept(candidate(), isOngoing = false, isGroupSummary = false)
        assertNotNull(accepted)
        assertEquals("com.example.messenger", accepted?.pkg)
        assertEquals("hello", accepted?.text)
    }

    @Test
    fun `de-duplicates the same key and timestamp`() {
        val filter = filter()
        assertNotNull(filter.accept(candidate(), isOngoing = false, isGroupSummary = false))
        assertNull(filter.accept(candidate(), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `the same key at a later time is a new notification`() {
        val filter = filter()
        assertNotNull(filter.accept(candidate(postedAt = 1), isOngoing = false, isGroupSummary = false))
        assertNotNull(filter.accept(candidate(postedAt = 2), isOngoing = false, isGroupSummary = false))
    }

    @Test
    fun `truncates the body to two KiB`() {
        val huge = "가".repeat(5000) // 15 KB in UTF-8
        val accepted = filter().accept(candidate(text = huge), isOngoing = false, isGroupSummary = false)
        val bytes = accepted!!.text.toByteArray(Charsets.UTF_8).size

        assertTrue(bytes <= NotificationFilter.MAX_TEXT_BYTES, "was $bytes bytes")
        assertTrue(accepted.text.isNotEmpty())
        assertTrue(!accepted.text.contains('\uFFFD'))
    }

    @Test
    fun `dedupe cache is bounded`() {
        val filter = NotificationFilter(
            allowedPackages = { setOf("com.example.messenger") },
            selfPackage = selfPackage,
            dedupeCapacity = 3,
        )
        for (index in 1..10) {
            assertNotNull(
                filter.accept(
                    candidate(key = "k$index", postedAt = index.toLong()),
                    isOngoing = false,
                    isGroupSummary = false,
                )
            )
        }
        // The oldest entries were evicted, so replaying the first key is accepted again.
        assertNotNull(filter.accept(candidate(key = "k1", postedAt = 1), isOngoing = false, isGroupSummary = false))
    }
}
