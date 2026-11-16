package kr.jjw.graft.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** How a status should read, independent of the colour that represents it. */
enum class StatusTone {
    Positive,
    Neutral,
    Warning,
    Negative,
}

/**
 * The dark-only palette.
 *
 * There is no light variant by design: the product chose a single, predictable appearance, so the
 * system light setting is ignored inside the app.
 */
object GraftPalette {
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF151A21)
    val surfaceVariant = Color(0xFF1C232C)
    val primary = Color(0xFF5B8CFF)
    val onSurface = Color(0xFFE6EAF0)
    val onSurfaceVariant = Color(0xFF9BA6B4)
    val outline = Color(0xFF2A3240)

    /** Connection is healthy. */
    val connected = Color(0xFF35C46B)

    /** Needs attention but still works. */
    val warning = Color(0xFFF0A63C)

    /** Failed, refused, or refused to re-pair. */
    val error = Color(0xFFF2555A)

    fun colorFor(tone: StatusTone): Color = when (tone) {
        StatusTone.Positive -> connected
        StatusTone.Neutral -> onSurfaceVariant
        StatusTone.Warning -> warning
        StatusTone.Negative -> error
    }
}

/** One spacing scale, so screens do not invent their own rhythm. */
object GraftSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

@Composable
fun GraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GraftPalette.primary,
            background = GraftPalette.background,
            surface = GraftPalette.surface,
            surfaceVariant = GraftPalette.surfaceVariant,
            onSurface = GraftPalette.onSurface,
            onSurfaceVariant = GraftPalette.onSurfaceVariant,
            outline = GraftPalette.outline,
            error = GraftPalette.error,
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
        content = content,
    )
}
