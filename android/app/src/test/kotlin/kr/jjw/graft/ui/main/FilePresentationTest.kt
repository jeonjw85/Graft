package kr.jjw.graft.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FilePresentationTest {
    @Test
    fun `the badge uses the extension`() {
        assertEquals("PDF", FilesScreenRules.badgeFor("design-spec.pdf"))
        assertEquals("JPG", FilesScreenRules.badgeFor("photo.JPG"))
        assertEquals("BIN", FilesScreenRules.badgeFor("sample.bin"))
    }

    @Test
    fun `a file without an extension gets a generic badge`() {
        assertEquals("FILE", FilesScreenRules.badgeFor("README"))
        assertEquals("TAR", FilesScreenRules.badgeFor("archive.tar"))
        assertEquals("FILE", FilesScreenRules.badgeFor("name.with.a.long.extension".substringAfterLast('.')))
    }

    @Test
    fun `relative time reads naturally`() {
        val now = 1_762_752_000_000L
        assertEquals("just now", FilesScreenRules.relativeTime(now, now - 10_000))
        assertEquals("2 min ago", FilesScreenRules.relativeTime(now, now - 120_000))
        assertEquals("3 h ago", FilesScreenRules.relativeTime(now, now - 3 * 3_600_000))
        assertEquals("2 d ago", FilesScreenRules.relativeTime(now, now - 2 * 86_400_000L))
    }
}
