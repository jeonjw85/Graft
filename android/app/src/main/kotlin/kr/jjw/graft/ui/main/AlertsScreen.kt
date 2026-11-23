package kr.jjw.graft.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.jjw.graft.R
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.notifications.NotificationAllowList
import kr.jjw.graft.notifications.PostedNotification
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.ui.components.EmptyState
import kr.jjw.graft.ui.components.RowDivider
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.SettingsGroup
import kr.jjw.graft.ui.components.SettingsRow
import kr.jjw.graft.ui.service.installedApps
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

data class AlertsScreenState(
    val accessGranted: Boolean,
    val recent: List<PostedNotification>,
)

object AlertsScreenRules {
    const val MAX_RECENT = 5

    fun from(accessGranted: Boolean, recent: List<PostedNotification>): AlertsScreenState =
        AlertsScreenState(accessGranted = accessGranted, recent = recent.take(MAX_RECENT))

    /**
     * The rows the "Forward these apps" card draws, in the user's chosen order.
     *
     * The allow list is the source of truth, not the launcher list: an app that was uninstalled or
     * lost its launch icon must still be shown so it can be switched off.
     */
    fun chosenRows(allowed: Set<String>, installed: List<Pair<String, String>>): List<Pair<String, String>> {
        val labels = installed.toMap()
        return allowed
            .map { pkg -> pkg to (labels[pkg] ?: pkg) }
            .sortedBy { it.second.lowercase() }
    }
}

@Composable
fun AlertsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by ConnectionRepository.state.collectAsState()
    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updateAllowed(pkg: String, checked: Boolean) {
        scope.launch {
            if (checked) {
                NotificationAllowList.add(context, pkg)
            } else {
                NotificationAllowList.remove(context, pkg)
            }
            allowed = NotificationAllowList.load(context)
        }
    }

    // Also runs on the way back from the system permission screen, so newly installed apps appear
    // in the picker without restarting the app.
    LifecycleResumeEffect(Unit) {
        accessGranted = NotificationAccess.isGranted(context)
        scope.launch {
            NotificationAllowList.refresh(context)
            allowed = NotificationAllowList.load(context)
            installed = withContext(Dispatchers.IO) { installedApps(context) }
        }
        onPauseOrDispose { }
    }

    val state = AlertsScreenRules.from(accessGranted, connection.recentNotifications)

    if (picking) {
        AppPickerDialog(
            installed = installed,
            allowed = allowed,
            onToggle = ::updateAllowed,
            onDismiss = { picking = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GraftSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.s),
        contentPadding = PaddingValues(
            top = GraftSpacing.xl,
            bottom = GraftSpacing.xl,
        ),
    ) {
        item {
            Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            AccessCard(
                granted = state.accessGranted,
                onOpenSettings = { NotificationAccess.openSettings(context) },
            )
        }

        if (state.accessGranted) {
            item { SectionHeader(stringResource(R.string.alerts_forward_apps)) }
            val chosen = AlertsScreenRules.chosenRows(allowed, installed)
            item {
                SettingsGroup {
                    chosen.forEachIndexed { index, (pkg, label) ->
                        if (index > 0) RowDivider()
                        AppToggleRow(
                            packageName = pkg,
                            label = label,
                            checked = true,
                            onCheckedChange = { checked -> updateAllowed(pkg, checked) },
                        )
                    }
                    if (chosen.isNotEmpty()) RowDivider()
                    SettingsRow(
                        label = stringResource(R.string.alerts_add_apps),
                        supporting = stringResource(R.string.alerts_add_apps_desc),
                        onClick = { picking = true },
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.alerts_recent)) }
        if (state.recent.isEmpty()) {
            item { EmptyState(stringResource(R.string.alerts_empty)) }
        } else {
            item {
                SettingsGroup {
                    state.recent.forEachIndexed { index, notification ->
                        if (index > 0) RowDivider()
                        RecentAlertRow(notification)
                    }
                }
            }
        }
    }
}

/** Searchable picker, so the tab shows only the choices the user made. */
@Composable
private fun AppPickerDialog(
    installed: List<Pair<String, String>>,
    allowed: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(query, installed) {
        if (query.isBlank()) installed
        else installed.filter { it.second.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = GraftPalette.surface, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(GraftSpacing.l),
            ) {
                Text(stringResource(R.string.alerts_forward_apps), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(GraftSpacing.s))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.action_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(GraftSpacing.s))
                if (visible.isEmpty()) {
                    EmptyState(
                        if (installed.isEmpty()) {
                            stringResource(R.string.alerts_loading_apps)
                        } else {
                            stringResource(R.string.alerts_no_match)
                        }
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(visible, key = { it.first }) { (pkg, label) ->
                            AppToggleRow(
                                packageName = pkg,
                                label = label,
                                checked = allowed.contains(pkg),
                                onCheckedChange = { onToggle(pkg, it) },
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

/** One card that says whether forwarding is on, instead of a paragraph plus a large button. */
@Composable
private fun AccessCard(granted: Boolean, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GraftPalette.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        onClick = onOpenSettings,
    ) {
        Row(
            modifier = Modifier.padding(GraftSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
        ) {
            Icon(
                imageVector = GraftIcons.Alerts,
                contentDescription = null,
                tint = if (granted) GraftPalette.connected else GraftPalette.warning,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (granted) {
                        stringResource(R.string.alerts_access_allowed)
                    } else {
                        stringResource(R.string.alerts_access_off)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (granted) {
                        stringResource(R.string.alerts_access_allowed_desc)
                    } else {
                        stringResource(R.string.alerts_access_off_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = GraftPalette.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = GraftPalette.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppToggleRow(
    packageName: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        AppIcon(packageName = packageName)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RecentAlertRow(notification: PostedNotification) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
    ) {
        AppIcon(packageName = notification.pkg)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notification.app, style = MaterialTheme.typography.labelMedium)
            Text(
                notification.title.ifBlank { notification.text },
                style = MaterialTheme.typography.bodySmall,
                color = GraftPalette.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The app's own launcher icon, loaded off the main thread at the density it will be drawn at. */
@Composable
private fun AppIcon(packageName: String, size: Int = 28) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { (size.dp).roundToPx() }
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, pixels) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(pixels, pixels)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    icon?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size.dp))
    }
}
