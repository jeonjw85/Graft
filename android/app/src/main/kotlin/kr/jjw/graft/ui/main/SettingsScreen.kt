package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.jjw.graft.clipboard.ClipboardAutoCopy
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.ui.PermissionGuidance
import kr.jjw.graft.ui.components.RowDivider
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.SettingsGroup
import kr.jjw.graft.ui.components.SettingsRow
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * Preferences, grouped the same way as the connection actions: one card per topic, one row per
 * choice. Nothing here is a bare link floating in space.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.m),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        Column {
            TextButton(onClick = onClose) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        SectionHeader("Background")
        SettingsGroup { BatteryRow() }

        SectionHeader("Clipboard")
        SettingsGroup { ClipboardRow() }

        SectionHeader("Notifications")
        SettingsGroup { NotificationAccessRow() }

        SectionHeader("About")
        SettingsGroup {
            SettingsRow(
                label = "Graft ${versionName()}",
                supporting = "Protocol 1 · LAN only · TLS 1.3 with pinned certificates",
                onClick = {},
                showChevron = false,
            )
        }

        Text(
            "Graft never sends your files, clipboard, or notifications to a server.",
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
        )
    }
}

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.3"
    }
}

@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember {
        mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    LifecycleResumeEffect(Unit) {
        exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }

    val needed = PermissionGuidance.shouldSuggestBatterySettings(exempt = exempt, paired = true)
    SettingsRow(
        label = if (needed) "Allow background running" else "Background running allowed",
        supporting = if (needed) {
            "Android may stop the link to save power. Tap to allow."
        } else {
            "Android will not stop the link to save power."
        },
        onClick = {
            if (needed) {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            }
        },
        showChevron = needed,
    )
}

@Composable
private fun ClipboardRow() {
    val context = LocalContext.current
    val state by ConnectionRepository.state.collectAsState()
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val autoCopy = remember { ClipboardAutoCopy() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(state.lastClipboard, state.lastClipboardAt, lifecycleState) {
        val text = state.lastClipboard ?: return@LaunchedEffect
        val shouldCopy = autoCopy.shouldCopy(
            text = text,
            arrivedAtMillis = state.lastClipboardAt,
            hasFocus = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
            nowMillis = System.currentTimeMillis(),
        )
        if (shouldCopy) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graft", text))
            copied = true
        }
    }

    SettingsRow(
        label = if (copied) "Copied to clipboard" else "Copy last received",
        supporting = state.lastClipboard?.take(80) ?: "Nothing received yet.",
        onClick = {
            state.lastClipboard?.let {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graft", it))
                copied = true
            }
        },
        showChevron = false,
    )
    RowDivider()
    SettingsRow(
        label = "Send clipboard to Mac",
        supporting = state.lastSentClipboard?.let { "Last sent: ${it.take(40)}" },
        onClick = {
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            ConnectionService.sendClipboard(text)
        },
        showChevron = false,
    )
}

@Composable
private fun NotificationAccessRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    LifecycleResumeEffect(Unit) {
        granted = NotificationAccess.isGranted(context)
        onPauseOrDispose { }
    }

    SettingsRow(
        label = if (granted) "Notification access allowed" else "Allow notification access",
        supporting = if (granted) {
            "Tick apps on the Alerts tab to forward their notifications."
        } else {
            "Needed to show phone alerts on your Mac."
        },
        onClick = { NotificationAccess.openSettings(context) },
        showChevron = !granted,
    )
}
