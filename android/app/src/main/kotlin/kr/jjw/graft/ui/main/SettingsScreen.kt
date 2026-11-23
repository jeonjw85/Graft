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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import kr.jjw.graft.R
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
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }

        SectionHeader(stringResource(R.string.settings_section_background))
        SettingsGroup { BatteryRow() }

        SectionHeader(stringResource(R.string.settings_section_clipboard))
        SettingsGroup { ClipboardRow() }

        SectionHeader(stringResource(R.string.settings_section_notifications))
        SettingsGroup { NotificationAccessRow() }

        SectionHeader(stringResource(R.string.settings_section_about))
        SettingsGroup {
            SettingsRow(
                label = "Graft ${versionName()}",
                supporting = stringResource(R.string.settings_about_desc),
                onClick = {},
                showChevron = false,
            )
        }

        Text(
            stringResource(R.string.settings_privacy_footer),
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
        label = if (needed) {
            stringResource(R.string.settings_battery_allow)
        } else {
            stringResource(R.string.settings_battery_allowed)
        },
        supporting = if (needed) {
            stringResource(R.string.settings_battery_allow_desc)
        } else {
            stringResource(R.string.settings_battery_allowed_desc)
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
    var copied by remember(state.lastClipboardAt) { mutableStateOf(false) }

    SettingsRow(
        label = if (copied) {
            stringResource(R.string.clipboard_copied)
        } else {
            stringResource(R.string.clipboard_copy_last_received)
        },
        supporting = state.lastClipboard?.take(80) ?: stringResource(R.string.clipboard_nothing_received),
        onClick = {
            state.lastClipboard?.let {
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Graft", it))
                copied = true
            }
        },
        showChevron = false,
    )
    RowDivider()
    SettingsRow(
        label = stringResource(R.string.action_send_clipboard),
        supporting = state.lastSentClipboard?.let { stringResource(R.string.clipboard_last_sent, it.take(40)) },
        onClick = {
            val text = clipboard?.primaryClip
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
        label = if (granted) {
            stringResource(R.string.alerts_access_allowed)
        } else {
            stringResource(R.string.settings_notifications_allow)
        },
        supporting = if (granted) {
            stringResource(R.string.settings_notifications_allowed_desc)
        } else {
            stringResource(R.string.settings_notifications_allow_desc)
        },
        onClick = { NotificationAccess.openSettings(context) },
        showChevron = !granted,
    )
}
