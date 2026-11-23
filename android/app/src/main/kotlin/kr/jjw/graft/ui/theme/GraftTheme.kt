package kr.jjw.graft.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    // Material fills roles the palette does not name explicitly; every one of them would otherwise
    // fall back to the light purple baseline.
    val onPrimary = Color(0xFF0B1020)
    val surfaceContainer = Color(0xFF1C232C)
    val secondaryContainer = Color(0xFF24304A)
    val onSecondaryContainer = onSurface
    val inverseSurface = onSurface
    val inverseOnSurface = background

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
    val base = Typography()
    val typography = base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.25).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.15).sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        ),
    )

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
            onPrimary = GraftPalette.onPrimary,
            surfaceContainer = GraftPalette.surfaceContainer,
            surfaceContainerHigh = GraftPalette.surfaceContainer,
            surfaceContainerHighest = GraftPalette.surfaceContainer,
            surfaceContainerLow = GraftPalette.surface,
            surfaceContainerLowest = GraftPalette.background,
            secondaryContainer = GraftPalette.secondaryContainer,
            onSecondaryContainer = GraftPalette.onSecondaryContainer,
            inverseSurface = GraftPalette.inverseSurface,
            inverseOnSurface = GraftPalette.inverseOnSurface,
            onError = GraftPalette.onPrimary,
        ),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
        ),
        content = content,
    )
}
