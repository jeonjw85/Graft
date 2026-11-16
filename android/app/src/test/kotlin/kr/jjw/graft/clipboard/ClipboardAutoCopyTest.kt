package kr.jjw.graft.clipboard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipboardAutoCopyTest {
    private val autoCopy = ClipboardAutoCopy(windowMillis = 5_000)

    @Test
    fun `copies once when focused`() {
        assertTrue(autoCopy.shouldCopy("hello", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 1_000))
        assertFalse(autoCopy.shouldCopy("hello", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 1_100))
    }

    @Test
    fun `does not copy without focus`() {
        assertFalse(autoCopy.shouldCopy("hello", arrivedAtMillis = 1_000, hasFocus = false, nowMillis = 1_000))
    }

    @Test
    fun `does not copy a stale arrival`() {
        assertFalse(autoCopy.shouldCopy("hello", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 6_500))
    }

    @Test
    fun `a new text copies again`() {
        assertTrue(autoCopy.shouldCopy("first", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 1_000))
        assertTrue(autoCopy.shouldCopy("second", arrivedAtMillis = 2_000, hasFocus = true, nowMillis = 2_000))
    }

    @Test
    fun `an empty text is ignored`() {
        assertFalse(autoCopy.shouldCopy("", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 1_000))
    }

    @Test
    fun `text that arrived while backgrounded is not pasted later`() {
        // Arrived at 1s while the app was in the background; the user returns at 20s.
        assertFalse(autoCopy.shouldCopy("secret", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 20_000))
        assertFalse(autoCopy.shouldCopy("secret", arrivedAtMillis = 1_000, hasFocus = true, nowMillis = 21_000))
    }
}
