package kr.jjw.graft.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransferIdsTest {
    @Test
    fun `the first transfer starts at one`() {
        assertEquals(1u, TransferIds.next(emptySet()))
    }

    @Test
    fun `a new id never collides with an active transfer`() {
        assertEquals(4u, TransferIds.next(setOf(1u, 3u)))
    }

    @Test
    fun `ids keep increasing after a completed transfer`() {
        assertEquals(8u, TransferIds.next(setOf(2u, 7u)))
    }
}
