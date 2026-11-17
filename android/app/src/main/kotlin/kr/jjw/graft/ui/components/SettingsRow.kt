package kr.jjw.graft.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * One grouped action: label, optional supporting line, and a chevron.
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            Text("›", style = MaterialTheme.typography.titleMedium, color = GraftPalette.onSurfaceVariant)
        }
    }
}

/** A card that groups rows, drawn as one surface rather than separate floating controls. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GraftPalette.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column { content() }
    }
}

@Composable
fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = GraftSpacing.l),
        color = GraftPalette.outline,
    )
}
