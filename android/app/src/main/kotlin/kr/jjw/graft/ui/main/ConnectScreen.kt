package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
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
import kr.jjw.graft.ui.PermissionGuidance
import kr.jjw.graft.service.ConnectionUiState
import kr.jjw.graft.clipboard.ClipboardAutoCopy
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.StatusCard
import kr.jjw.graft.ui.onboarding.PairingDialog
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import kr.jjw.graft.ui.theme.StatusTone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    onOpenSettings: () -> Unit,
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

        // Pairing another phone is rare once a link exists; it should not outrank the link itself.
        TextButton(
            onClick = {
                // A live session keeps the offer it captured at connect time, so adding a phone
                // only takes effect after the current link is closed.
                if (paired) ConnectionService.stop(context)
                pairingOpen = true
            }
        ) {
            Text("Pair another phone")
        }

        if (paired) {
            // Retrying only makes sense while the link is down.
            if (state.status !is ConnectionStatus.Connected) {
                OutlinedButton(
                    onClick = { ConnectionService.start(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reconnect")
                }
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
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        if (pairingOpen) {
            PairingDialog(
                onDismiss = {
                    pairingOpen = false
                    if (paired) ConnectionService.start(context)
                }
            )
        }
    }
}
