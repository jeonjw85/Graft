package kr.jjw.graft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/** One transfer, either in flight (progress) or finished (detail text). */
@Composable
fun TransferRow(
    name: String,
    detail: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = GraftSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = GraftPalette.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onOpen != null) {
            Spacer(Modifier.width(GraftSpacing.s))
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
