package kr.jjw.graft.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionNotificationTest {
    @Test
    fun `connected is quiet`() {
        val state = ConnectionNotificationState.forStatus("Connected", "Galaxy S24", transferring = false)

        assertTrue(state.ongoing)
        assertEquals("Galaxy S24", state.text)
    }

    @Test
    fun `transferring says so`() {
        val state = ConnectionNotificationState.forStatus("Connected", "Galaxy S24", transferring = true)

        assertTrue(state.text.contains("Sending"), state.text)
    }

    @Test
    fun `retrying keeps a countdown`() {
        val state = ConnectionNotificationState.forStatus("Retrying in 8s", null, transferring = false)

        assertEquals("Retrying in 8s", state.text)
    }

    @Test
    fun `disconnected is not ongoing, so the user can swipe it away`() {
        val state = ConnectionNotificationState.forStatus("Disconnected", null, transferring = false)

        assertFalse(state.ongoing, "a stopped service must not pin a notification")
        assertEquals("Disconnected", state.text)
        assertTrue(state.alertOnce, "a quiet state should not re-alert")
    }
}
