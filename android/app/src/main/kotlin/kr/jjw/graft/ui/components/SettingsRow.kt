package kr.jjw.graft.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * One grouped action: optional leading icon badge, label, optional supporting line, and a chevron.
 *
 * Grouping actions into a card is what turns a column of loose links into something a person can
 * read at a glance.
 */
@Composable
fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    val resolvedTint = iconTint ?: if (destructive) GraftPalette.error else GraftPalette.onSurfaceVariant
    val iconContainer = if (destructive) {
        GraftPalette.error.copy(alpha = 0.14f)
    } else if (iconTint != null) {
        iconTint.copy(alpha = 0.14f)
    } else {
        GraftPalette.surfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(iconContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) GraftPalette.error else GraftPalette.onSurface,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = GraftPalette.onSurfaceVariant,
                )
            }
        }
        if (showChevron) {
            Icon(
                imageVector = GraftIcons.ChevronRight,
                contentDescription = null,
                tint = GraftPalette.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A card that groups rows, drawn as one surface rather than separate floating controls. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GraftPalette.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GraftPalette.outline),
    ) {
        Column { content() }
    }
}

@Composable
fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = GraftSpacing.l),
        thickness = 1.dp,
        color = GraftPalette.outline.copy(alpha = 0.65f),
    )
}
