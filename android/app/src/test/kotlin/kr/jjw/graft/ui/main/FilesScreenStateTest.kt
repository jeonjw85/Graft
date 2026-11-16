package kr.jjw.graft.ui.main

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferFailureKind
import kr.jjw.graft.files.TransferProgress
import kr.jjw.graft.service.TransferHistory
import kr.jjw.graft.service.TransferOutcome
import kr.jjw.graft.service.TransferRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilesScreenStateTest {
    private fun progress(id: UInt, name: String = "f$id") = TransferProgress(
        name = name,
        confirmed = 0,
        total = 10,
        direction = TransferDirection.receive,
    )

    private fun record(
        id: UInt,
        name: String = "f$id",
        destination: String? = "content://downloads/$id",
        reason: TransferFailureKind? = null,
    ) = TransferRecord(
        id = id,
        name = name,
        bytes = 1_024,
        direction = TransferDirection.receive,
        outcome = if (reason == null) TransferOutcome.Completed else TransferOutcome.Failed,
        reason = TransferHistory.messageFor(reason),
        destination = destination,
        finishedAtMillis = 1_000,
    )

    @Test
    fun `active transfers are ordered by id`() {
        val state = FilesScreenRules.from(
            transfers = mapOf(3u to progress(3u), 1u to progress(1u)),
            history = emptyList(),
        )

        assertEquals(listOf("f1", "f3"), state.active.map { it.name })
    }

    @Test
    fun `recent keeps at most fifty rows`() {
        val history = (1..60).map { record(it.toUInt()) }

        val state = FilesScreenRules.from(emptyMap(), history)

        assertEquals(50, state.recent.size)
        assertEquals(1u, state.recent.first().id)
    }

    @Test
    fun `sizes use binary units`() {
        assertEquals("0 B", FilesScreenRules.formatSize(0))
        assertEquals("999 B", FilesScreenRules.formatSize(999))
        assertEquals("1.0 KB", FilesScreenRules.formatSize(1024))
        assertEquals("5.0 MiB", FilesScreenRules.formatSize(5L * 1024 * 1024))
        assertEquals("1.5 GiB", FilesScreenRules.formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a received file with a content uri is openable`() {
        assertTrue(FilesScreenRules.canOpen("content://downloads/1"))
    }

    @Test
    fun `a directory fallback cannot be opened by another app`() {
        assertFalse(FilesScreenRules.canOpen("/data/user/0/kr.jjw.graft/files/received/a.txt"))
        assertFalse(FilesScreenRules.canOpen(null))
    }

    @Test
    fun `an app storage row says so`() {
        assertEquals(
            "Received  ·  1.0 KB  ·  Saved to app storage",
            FilesScreenRules.detail(record(1u, destination = "/data/a.txt")),
        )
    }

    @Test
    fun `a downloads row names the direction and size`() {
        assertEquals("Received  ·  1.0 KB", FilesScreenRules.detail(record(1u)))
    }

    @Test
    fun `a failed row leads with the reason`() {
        assertEquals(
            "Hash mismatch  ·  Received  ·  1.0 KB",
            FilesScreenRules.detail(record(1u, reason = TransferFailureKind.HASH_MISMATCH)),
        )
    }
}
