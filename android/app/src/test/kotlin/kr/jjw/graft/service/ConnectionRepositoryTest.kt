package kr.jjw.graft.service

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConnectionRepositoryTest {
    @BeforeEach
    fun reset() {
        ConnectionRepository.reset()
    }

    @Test
    fun `progress is published as a snapshot, not a live map`() {
        val live = mutableMapOf(
            1u to TransferProgress("a.bin", 0, 10, TransferDirection.receive)
        )
        ConnectionRepository.onTransferProgress(live)

        live[1u] = live.getValue(1u).copy(confirmed = 5)

        assertEquals(0, ConnectionRepository.state.value.transfers.getValue(1u).confirmed)
    }

    @Test
    fun `sending the clipboard does not overwrite what was received`() {
        ConnectionRepository.onClipboardReceived("from mac")

        ConnectionRepository.onSubmitClipboard("from phone")

        assertEquals("from mac", ConnectionRepository.state.value.lastClipboard)
        assertEquals("from phone", ConnectionRepository.state.value.lastSentClipboard)
    }

    @Test
    fun `a queued notification wakes the sender`() {
        var woken = 0
        ConnectionRepository.onPendingNotification = { woken += 1 }

        ConnectionRepository.onNotification(
            kr.jjw.graft.notifications.PostedNotification(
                pkg = "com.example",
                app = "Example",
                title = "Title",
                text = "Text",
                postedAt = 1,
                key = "key",
                priority = "normal",
            )
        )

        assertEquals(1, woken, "a live connection must send without waiting for a reconnect")
        assertEquals(1, ConnectionRepository.takePendingNotifications().size)
        ConnectionRepository.onPendingNotification = null
    }

    @Test
    fun `a reset forgets both clipboard sides`() {
        ConnectionRepository.onClipboardReceived("from mac")
        ConnectionRepository.onSubmitClipboard("from phone")

        ConnectionRepository.reset()

        assertNull(ConnectionRepository.state.value.lastClipboard)
        assertNull(ConnectionRepository.state.value.lastSentClipboard)
    }
}
