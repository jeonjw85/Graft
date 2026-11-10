package kr.jjw.graft.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress

/** Renders the active transfers. Pure so it can be previewed without a connection. */
@Composable
fun TransferList(
    transfers: Map<UInt, TransferProgress>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val ordered = transfers.entries.sortedBy { it.key }
        if (ordered.isEmpty()) {
            Text(
                "No transfers yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        for (entry in ordered) {
            val progress = entry.value
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (progress.direction == TransferDirection.send) "Sending" else "Receiving",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  ${progress.name}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(progress.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
