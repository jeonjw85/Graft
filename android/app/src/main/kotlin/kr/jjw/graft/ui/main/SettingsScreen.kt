package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * Preferences, grouped the same way as the connection actions: one card per topic, one row per
 * choice. Nothing here is a bare link floating in space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GraftPalette.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = GraftIcons.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GraftPalette.background,
                        titleContentColor = GraftPalette.onSurface,
                        navigationIconContentColor = GraftPalette.onSurface,
                    ),
                )
                HorizontalDivider(thickness = 1.dp, color = GraftPalette.outline.copy(alpha = 0.65f))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.m),
        ) {
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
                    icon = GraftIcons.Info,
                    iconTint = GraftPalette.primary,
                )
            }

            Text(
                stringResource(R.string.settings_privacy_footer),
                style = MaterialTheme.typography.bodySmall,
                color = GraftPalette.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = GraftSpacing.xs, vertical = GraftSpacing.xs),
            )
        }
    }
}

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.2.1"
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
        icon = GraftIcons.Battery,
        iconTint = if (needed) GraftPalette.warning else GraftPalette.connected,
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
        icon = GraftIcons.Copy,
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
        icon = GraftIcons.Clipboard,
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
        icon = GraftIcons.Alerts,
        iconTint = if (granted) GraftPalette.connected else GraftPalette.warning,
    )
}
