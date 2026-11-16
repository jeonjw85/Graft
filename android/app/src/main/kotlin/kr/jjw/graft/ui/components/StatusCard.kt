package kr.jjw.graft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
        color = GraftPalette.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(GraftSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToneDot(tone = tone, description = title)
            Spacer(Modifier.width(GraftSpacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs)) {
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

@Composable
private fun ToneDot(tone: StatusTone, description: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .background(GraftPalette.colorFor(tone), CircleShape)
            .semantics { contentDescription = description },
    )
}
