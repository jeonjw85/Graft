package kr.jjw.graft.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OutgoingFileStateTest {
    @Test
    fun `a successful offer clears the previous error`() {
        ConnectionRepository.reset()
        ConnectionRepository.onOutgoingFileResult(false, "The file could not be opened")

        ConnectionRepository.onOutgoingFileResult(true, null)

        assertNull(ConnectionRepository.state.value.outgoingError)
    }

    @Test
    fun `a failed offer exposes the reason`() {
        ConnectionRepository.reset()

        ConnectionRepository.onOutgoingFileResult(false, "Connect to your Mac first")

        assertEquals(
            "Connect to your Mac first",
            ConnectionRepository.state.value.outgoingError,
        )
    }

    @Test
    fun `a failure without a reason still shows something`() {
        ConnectionRepository.reset()

        ConnectionRepository.onOutgoingFileResult(false, null)

        assertEquals("Could not send the file", ConnectionRepository.state.value.outgoingError)
    }
}
