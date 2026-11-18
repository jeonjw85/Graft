package kr.jjw.graft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import kr.jjw.graft.ui.theme.StatusTone

/** The one place a connection, permission, or transfer state is announced at the top of a screen. */
@Composable
fun StatusCard(
    title: String,
    detail: String?,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GraftPalette.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(GraftSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
        ) {
            ToneBadge(tone = tone, description = title)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GraftPalette.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = GraftPalette.error)
                }
            }
        }
    }
}

/** A tinted circle behind the status icon: colour carries the tone, the icon repeats it. */
@Composable
private fun ToneBadge(tone: StatusTone, description: String) {
    val color = GraftPalette.colorFor(tone)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = 0.16f), CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = GraftIcons.forTone(tone),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
    }
}
