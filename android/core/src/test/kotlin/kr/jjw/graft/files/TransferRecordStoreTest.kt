package kr.jjw.graft.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TransferRecordStoreTest {
    @TempDir
    lateinit var directory: Path

    private var now = 1_762_752_000_000L

    private fun store(): TransferRecordStore =
        TransferRecordStore(File(directory.toFile(), "transfers.json"), clock = { now })

    private fun record(
        name: String = "보고서.pdf",
        size: Long = 1024,
        confirmed: Long = 512,
        dir: String = "/tmp/graft",
        at: Long = now,
    ) = ResumeRecord(
        name = name,
        directory = dir,
        size = size,
        bytesConfirmed = confirmed,
        updatedAtMillis = at,
    )

    @Test
    fun `round trips a record`() {
        val store = store()
        store.upsert(record())

        val found = store.find("보고서.pdf", "/tmp/graft", 1024, 512)
        assertEquals(512, found?.bytesConfirmed)
        assertEquals(1024, found?.size)
    }

    @Test
    fun `a fresh store reads what another instance wrote`() {
        store().upsert(record())

        val reopened = TransferRecordStore(
            File(directory.toFile(), "transfers.json"),
            clock = { now }
        )
        assertEquals(1, reopened.count())
    }

    @Test
    fun `expired records are ignored and deleted`() {
        val store = store()
        store.upsert(record(at = now))

        now += 25 * 60 * 60 * 1000

        assertNull(store.find("보고서.pdf", "/tmp/graft", 1024, 512))
        assertEquals(0, store.count(), "an expired record must be deleted, not merely ignored")
    }

    @Test
    fun `purge removes only expired records`() {
        val store = store()
        store.upsert(record(name = "old.bin", at = now - 25 * 60 * 60 * 1000))
        store.upsert(record(name = "new.bin", at = now))

        assertEquals(1, store.purgeExpired())
        assertEquals(1, store.count())
        assertEquals(0, store.find("old.bin", "/tmp/graft", 1024, 512)?.bytesConfirmed ?: 0)
    }

    @Test
    fun `a different name or size does not match`() {
        val store = store()
        store.upsert(record())

        assertNull(store.find("other.pdf", "/tmp/graft", 1024, 512))
        assertNull(store.find("보고서.pdf", "/tmp/graft", 2048, 512))
        assertNull(store.find("보고서.pdf", "/other/dir", 1024, 512))
    }

    @Test
    fun `a partial file of the wrong length does not match`() {
        val store = store()
        store.upsert(record(confirmed = 1024))

        assertNull(store.find("보고서.pdf", "/tmp/graft", 1024, 512))
    }

    @Test
    fun `zero bytes on disk does not match`() {
        val store = store()
        store.upsert(record(confirmed = 0))

        assertNull(store.find("보고서.pdf", "/tmp/graft", 1024, 0))
    }

    @Test
    fun `remove clears the record`() {
        val store = store()
        store.upsert(record())

        store.remove("보고서.pdf", "/tmp/graft")

        assertNull(store.find("보고서.pdf", "/tmp/graft", 1024, 512))
        assertEquals(0, store.count())
    }

    @Test
    fun `upsert replaces the same name and directory`() {
        val store = store()
        store.upsert(record(confirmed = 256))
        store.upsert(record(confirmed = 512))

        assertEquals(1, store.count())
        assertEquals(512, store.find("보고서.pdf", "/tmp/graft", 1024, 512)?.bytesConfirmed)
    }
}
