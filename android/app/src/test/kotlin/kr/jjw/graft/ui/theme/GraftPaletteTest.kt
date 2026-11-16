package kr.jjw.graft.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GraftPaletteTest {
    @Test
    fun `the palette matches the spec`() {
        assertEquals(Color(0xFF0E1116), GraftPalette.background)
        assertEquals(Color(0xFF151A21), GraftPalette.surface)
        assertEquals(Color(0xFF1C232C), GraftPalette.surfaceVariant)
        assertEquals(Color(0xFF5B8CFF), GraftPalette.primary)
        assertEquals(Color(0xFFE6EAF0), GraftPalette.onSurface)
        assertEquals(Color(0xFF9BA6B4), GraftPalette.onSurfaceVariant)
        assertEquals(Color(0xFF2A3240), GraftPalette.outline)
        assertEquals(Color(0xFF35C46B), GraftPalette.connected)
        assertEquals(Color(0xFFF0A63C), GraftPalette.warning)
        assertEquals(Color(0xFFF2555A), GraftPalette.error)
    }

    @Test
    fun `the scheme has no light baseline roles left`() {
        // Material 3 defaults are purple/light; these are the roles a filled button, the navigation
        // bar, the selected tab pill, and the snackbar use, and each must be part of this palette.
        assertEquals(Color(0xFF0B1020), GraftPalette.onPrimary)
        assertEquals(GraftPalette.surfaceVariant, GraftPalette.surfaceContainer)
        assertEquals(Color(0xFF24304A), GraftPalette.secondaryContainer)
        assertEquals(GraftPalette.onSurface, GraftPalette.onSecondaryContainer)
        assertEquals(GraftPalette.onSurface, GraftPalette.inverseSurface)
        assertEquals(GraftPalette.background, GraftPalette.inverseOnSurface)
    }

    @Test
    fun `tones map to state colours`() {
        assertEquals(GraftPalette.connected, GraftPalette.colorFor(StatusTone.Positive))
        assertEquals(GraftPalette.onSurfaceVariant, GraftPalette.colorFor(StatusTone.Neutral))
        assertEquals(GraftPalette.warning, GraftPalette.colorFor(StatusTone.Warning))
        assertEquals(GraftPalette.error, GraftPalette.colorFor(StatusTone.Negative))
    }
}
