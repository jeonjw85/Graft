package kr.jjw.graft.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jjw.graft.R
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.service.ConnectionStatus
import kr.jjw.graft.service.ConnectionUiState
import kr.jjw.graft.ui.components.RowDivider
import kr.jjw.graft.ui.components.SettingsGroup
import kr.jjw.graft.ui.components.SettingsRow
import kr.jjw.graft.ui.components.StatusCard
import kr.jjw.graft.ui.onboarding.PairingDialog
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import kr.jjw.graft.ui.theme.StatusTone

/** What the status card says for a given connection snapshot. */
data class ConnectVisual(val title: String, val detail: String?, val tone: StatusTone, val error: String?)

fun visualFor(state: ConnectionUiState): ConnectVisual = when (val status = state.status) {
    is ConnectionStatus.Connected -> ConnectVisual(
        title = "Connected to ${state.peerName ?: status.peer}",
        detail = "Ready to send files, clipboard text, and alerts.",
        tone = StatusTone.Positive,
        error = null,
    )
    is ConnectionStatus.Connecting -> ConnectVisual(
        title = "Connecting…",
        detail = state.lastError ?: "Looking for your Mac on this Wi-Fi.",
        tone = StatusTone.Warning,
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
        detail = "Open Graft on your Mac to connect.",
        tone = StatusTone.Neutral,
        error = null,
    )
}

@Composable
fun ConnectScreen(
    paired: Boolean,
    onForget: () -> Unit,
    onOpenSettings: () -> Unit,
    onSendFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by ConnectionRepository.state.collectAsState()
    var pairingOpen by remember { mutableStateOf(false) }
    var clipboardFeedback by remember { mutableStateOf<String?>(null) }
    val visual = visualFor(state)
    val connected = state.status is ConnectionStatus.Connected
    val clipboard = remember(context) {
        context.getSystemService(android.content.ClipboardManager::class.java)
    }
    val sentMessage = stringResource(R.string.clipboard_sent_to_mac)

    val localizedStatusTitle = when (val status = state.status) {
        is ConnectionStatus.Connected -> stringResource(
            R.string.status_connected,
            state.peerName ?: status.peer,
        )
        is ConnectionStatus.Connecting -> stringResource(R.string.status_connecting)
        is ConnectionStatus.Failed, ConnectionStatus.Idle -> stringResource(R.string.status_not_connected)
    }
    val localizedStatusDetail = when (state.status) {
        is ConnectionStatus.Connected -> stringResource(R.string.status_connected_detail)
        is ConnectionStatus.Connecting -> state.lastError ?: stringResource(R.string.status_connecting_detail)
        is ConnectionStatus.Failed -> null
        ConnectionStatus.Idle -> stringResource(R.string.status_idle_detail)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.connection_title),
                style = MaterialTheme.typography.headlineSmall,
                color = GraftPalette.onSurface,
            )
            if (paired) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = GraftIcons.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                        tint = GraftPalette.onSurfaceVariant,
                    )
                }
            }
        }

        StatusCard(
            title = localizedStatusTitle,
            detail = localizedStatusDetail,
            tone = visual.tone,
            error = visual.error,
        )

        // Two-column quick action tiles when connected; single primary CTA otherwise.
        if (paired && connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
            ) {
                QuickActionTile(
                    label = stringResource(R.string.action_send_file_to_mac),
                    icon = GraftIcons.Upload,
                    accent = GraftPalette.primary,
                    emphasized = true,
                    onClick = onSendFile,
                    modifier = Modifier.weight(1f),
                )
                QuickActionTile(
                    label = stringResource(R.string.action_send_clipboard),
                    supporting = clipboardFeedback
                        ?: state.lastSentClipboard?.let {
                            stringResource(R.string.clipboard_last_sent, it.take(24))
                        },
                    icon = GraftIcons.Clipboard,
                    accent = GraftPalette.onSurface,
                    emphasized = false,
                    onClick = {
                        val text = clipboard?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        clipboardFeedback = ConnectionService.sendClipboard(text) ?: sentMessage
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (paired) {
            Button(
                onClick = { ConnectionService.start(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GraftPalette.primary,
                    contentColor = GraftPalette.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = GraftIcons.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(GraftSpacing.s))
                Text(stringResource(R.string.action_reconnect))
            }
        } else {
            Button(
                onClick = { pairingOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GraftPalette.primary,
                    contentColor = GraftPalette.onPrimary,
                ),
            ) {
                Icon(
                    imageVector = GraftIcons.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(GraftSpacing.s))
                Text(stringResource(R.string.action_pair_mac))
            }
        }

        if (state.lastClipboard != null) {
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.action_copy_last_clipboard),
                    supporting = state.lastClipboard?.take(60),
                    onClick = {
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Graft", state.lastClipboard)
                        )
                    },
                    showChevron = false,
                    icon = GraftIcons.Copy,
                    iconTint = GraftPalette.primary,
                )
            }
        }

        if (paired) {
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.action_pair_another_mac),
                    supporting = stringResource(R.string.action_pair_another_mac_desc),
                    onClick = {
                        if (paired) ConnectionService.stop(context)
                        pairingOpen = true
                    },
                    icon = GraftIcons.QrCode,
                )
                // A retry loop that cannot be stopped is a trap; offer the exit in both states.
                if (state.status !is ConnectionStatus.Idle) {
                    RowDivider()
                    SettingsRow(
                        label = if (connected) {
                            stringResource(R.string.action_disconnect)
                        } else {
                            stringResource(R.string.action_stop_trying)
                        },
                        supporting = if (connected) {
                            stringResource(R.string.action_disconnect_desc)
                        } else {
                            stringResource(R.string.action_stop_trying_desc)
                        },
                        onClick = { ConnectionService.stop(context) },
                        showChevron = false,
                        icon = GraftIcons.Power,
                    )
                }
                RowDivider()
                SettingsRow(
                    label = stringResource(R.string.action_settings),
                    supporting = stringResource(R.string.action_settings_desc),
                    onClick = onOpenSettings,
                    icon = GraftIcons.Settings,
                )
                RowDivider()
                SettingsRow(
                    label = stringResource(R.string.action_forget_mac),
                    supporting = stringResource(R.string.action_forget_mac_desc),
                    destructive = true,
                    onClick = {
                        ConnectionService.forget(context)
                        onForget()
                    },
                    showChevron = false,
                    icon = GraftIcons.Delete,
                )
            }
        }

        Text(
            stringResource(R.string.privacy_lan_footer),
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = GraftSpacing.xs),
        )

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

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val container = if (emphasized) GraftPalette.secondaryContainer else GraftPalette.surface
    val border = if (emphasized) GraftPalette.primary.copy(alpha = 0.45f) else GraftPalette.outline
    val badgeBg = if (emphasized) GraftPalette.primary.copy(alpha = 0.2f) else GraftPalette.surfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(GraftSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.s),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(badgeBg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = GraftPalette.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = GraftPalette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
