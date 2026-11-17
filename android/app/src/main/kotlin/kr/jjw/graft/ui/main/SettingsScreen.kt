package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.jjw.graft.clipboard.ClipboardAutoCopy
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.ui.PermissionGuidance
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * Everything that is a preference rather than a link action.
 *
 * Keeping these off the Connection tab is what lets that screen say one thing: whether the phone and
 * the Mac are talking.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    var aboutOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GraftSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onClose) { Text("Back") }

        SectionHeader("Background")
        BatterySection()

        SectionHeader("Clipboard")
        ClipboardSection()

        SectionHeader("Notifications")
        NotificationAccessSection()

        SectionHeader("About")
        AboutSection(expanded = aboutOpen, onToggle = { aboutOpen = !aboutOpen })
    }
}

@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember {
        mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }

    // The user leaves for system settings; refresh the answer when they come back.
    LifecycleResumeEffect(Unit) {
        exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }

    if (!PermissionGuidance.shouldSuggestBatterySettings(exempt = exempt, paired = true)) {
        Text(
            "Graft may run in the background without restrictions.",
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
        )
        return
    }
    Text(
        "Android may stop the connection to save power. Allow Graft to run in the background.",
        style = MaterialTheme.typography.bodySmall,
        color = GraftPalette.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    ) {
        Text("Open battery settings")
    }
}

@Composable
private fun ClipboardSection() {
    val context = LocalContext.current
    val state by ConnectionRepository.state.collectAsState()
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val autoCopy = remember { ClipboardAutoCopy() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffectClipboard(
        received = state.lastClipboard,
        receivedAt = state.lastClipboardAt,
        lifecycleState = lifecycleState,
        autoCopy = autoCopy,
        onCopy = { text ->
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graft", text))
            copied = true
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs)) {
        Text(
            state.lastClipboard?.take(200) ?: "Nothing received yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = GraftPalette.onSurfaceVariant,
        )
        if (copied) {
            Text("Copied to clipboard", style = MaterialTheme.typography.bodySmall, color = GraftPalette.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(GraftSpacing.s)) {
            state.lastClipboard?.let { text ->
                OutlinedButton(onClick = {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graft", text))
                    copied = true
                }) { Text("Copy") }
            }
            OutlinedButton(onClick = {
                val text = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                ConnectionService.sendClipboard(text)
            }) { Text("Send clipboard") }
        }
        state.lastSentClipboard?.let {
            Text(
                "Last sent: ${it.take(80)}",
                style = MaterialTheme.typography.bodySmall,
                color = GraftPalette.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LaunchedEffectClipboard(
    received: String?,
    receivedAt: Long,
    lifecycleState: Lifecycle.State,
    autoCopy: ClipboardAutoCopy,
    onCopy: (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(received, receivedAt, lifecycleState) {
        val text = received ?: return@LaunchedEffect
        val shouldCopy = autoCopy.shouldCopy(
            text = text,
            arrivedAtMillis = receivedAt,
            hasFocus = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
            nowMillis = System.currentTimeMillis(),
        )
        if (shouldCopy) onCopy(text)
    }
}

@Composable
private fun NotificationAccessSection() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    LifecycleResumeEffect(Unit) {
        granted = NotificationAccess.isGranted(context)
        onPauseOrDispose { }
    }

    Text(
        if (granted) {
            "Graft can forward the apps you tick on the Alerts tab."
        } else {
            "Grant notification access to show phone alerts on your Mac."
        },
        style = MaterialTheme.typography.bodySmall,
        color = GraftPalette.onSurfaceVariant,
    )
    OutlinedButton(onClick = { NotificationAccess.openSettings(context) }) {
        Text(if (granted) "Notification access settings" else "Grant notification access")
    }
}

@Composable
private fun AboutSection(expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.3"
    }
    Text("Graft $version", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onToggle) { Text(if (expanded) "Hide details" else "Protocol details") }
    if (expanded) {
        Text(
            "Protocol 1 · LAN only · TLS 1.3 with pinned certificates",
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
        )
    }
}
