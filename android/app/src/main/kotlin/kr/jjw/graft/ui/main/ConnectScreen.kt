package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.service.ConnectionStatus
import kr.jjw.graft.service.ConnectionUiState
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.StatusCard
import kr.jjw.graft.ui.onboarding.PairingDialog
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import kr.jjw.graft.ui.theme.StatusTone

/** What the status card says for a given connection snapshot. */
data class ConnectVisual(val title: String, val detail: String?, val tone: StatusTone, val error: String?)

fun visualFor(state: ConnectionUiState): ConnectVisual = when (val status = state.status) {
    is ConnectionStatus.Connected -> ConnectVisual(
        title = "Connected to ${state.peerName ?: status.peer}",
        detail = "Your phone can send files, clipboard text, and alerts.",
        tone = StatusTone.Positive,
        error = null,
    )
    is ConnectionStatus.Connecting -> ConnectVisual(
        title = "Connecting…",
        detail = state.lastError,
        tone = StatusTone.Neutral,
        error = null,
    )
    is ConnectionStatus.Failed -> ConnectVisual(
        title = "Not connected",
        detail = null,
        tone = StatusTone.Negative,
        error = status.message,
    )
    ConnectionStatus.Idle -> ConnectVisual(
        title = "Not connected",
        detail = if (state.peerName == null) "Open the Mac app to connect." else null,
        tone = StatusTone.Neutral,
        error = null,
    )
}

@Composable
fun ConnectScreen(
    paired: Boolean,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by ConnectionRepository.state.collectAsState()
    var pairingOpen by remember { mutableStateOf(false) }
    val visual = visualFor(state)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GraftSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        StatusCard(
            title = visual.title,
            detail = visual.detail,
            tone = visual.tone,
            error = visual.error,
        )

        Button(onClick = { pairingOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Pair another phone")
        }

        if (paired) {
            OutlinedButton(
                onClick = { ConnectionService.start(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reconnect")
            }
            TextButton(onClick = { ConnectionService.stop(context) }) { Text("Disconnect") }
            TextButton(
                onClick = {
                    ConnectionService.forget(context)
                    onForget()
                }
            ) {
                Text("Forget this Mac")
            }

            SectionHeader("Clipboard")
            ClipboardCard(lastReceived = state.lastClipboard, lastSent = state.lastClipboard)
        }

        if (pairingOpen) {
            PairingDialog(onDismiss = { pairingOpen = false })
        }
    }
}

@Composable
private fun ClipboardCard(lastReceived: String?, lastSent: String?) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

    Column(verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs)) {
        Text(
            lastReceived?.take(160) ?: "Nothing received yet.",
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
        )
        Button(
            onClick = {
                val text = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                if (!text.isNullOrEmpty()) ConnectionService.sendClipboard(text)
            }
        ) {
            Text("Send clipboard")
        }
        lastSent?.let {
            Text(
                "Last shared: ${it.take(80)}",
                style = MaterialTheme.typography.bodySmall,
                color = GraftPalette.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AlertsPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(GraftSpacing.l)) {
        Text("Alerts", style = MaterialTheme.typography.titleMedium)
    }
}
