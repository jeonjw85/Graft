package kr.jjw.graft.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.notifications.NotificationAllowList
import kr.jjw.graft.notifications.PostedNotification
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.ui.components.EmptyState
import kr.jjw.graft.ui.components.RowDivider
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.SettingsGroup
import kr.jjw.graft.ui.components.SettingsRow
import kr.jjw.graft.ui.theme.GraftIcons
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import kr.jjw.graft.ui.service.installedApps

data class AlertsScreenState(
    val accessGranted: Boolean,
    val recent: List<PostedNotification>,
)

object AlertsScreenRules {
    const val MAX_RECENT = 5

    fun from(accessGranted: Boolean, recent: List<PostedNotification>): AlertsScreenState =
        AlertsScreenState(accessGranted = accessGranted, recent = recent.take(MAX_RECENT))
}

@Composable
fun AlertsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by ConnectionRepository.state.collectAsState()
    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        NotificationAllowList.refresh(context)
        allowed = NotificationAllowList.load(context)
        installed = installedApps(context)
    }

    LifecycleResumeEffect(Unit) {
        accessGranted = NotificationAccess.isGranted(context)
        onPauseOrDispose { }
    }

    val state = AlertsScreenRules.from(accessGranted, connection.recentNotifications)

    if (picking) {
        AppPickerDialog(
            installed = installed,
            allowed = allowed,
            onToggle = { pkg, checked ->
                scope.launch {
                    if (checked) {
                        NotificationAllowList.add(context, pkg)
                    } else {
                        NotificationAllowList.remove(context, pkg)
                    }
                    allowed = NotificationAllowList.load(context)
                }
            },
            onDismiss = { picking = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GraftSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.s),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = GraftSpacing.l,
            bottom = GraftSpacing.xl,
        ),
    ) {
        item {
            Text("Alerts", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            AccessCard(
                granted = state.accessGranted,
                onOpenSettings = { NotificationAccess.openSettings(context) },
            )
        }

        if (state.accessGranted) {
            item { SectionHeader("Forward these apps") }
            val chosen = installed.filter { allowed.contains(it.first) }
            item {
                SettingsGroup {
                    if (chosen.isEmpty()) {
                        SettingsRow(
                            label = "Add apps",
                            supporting = "Choose which apps may forward alerts",
                            onClick = { picking = true },
                            showChevron = false,
                        )
                    } else {
                        chosen.forEachIndexed { index, (pkg, label) ->
                            if (index > 0) RowDivider()
                            AppToggleRow(
                                packageName = pkg,
                                label = label,
                                checked = true,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        if (checked) {
                                            NotificationAllowList.add(context, pkg)
                                        } else {
                                            NotificationAllowList.remove(context, pkg)
                                        }
                                        allowed = NotificationAllowList.load(context)
                                    }
                                },
                            )
                        }
                        RowDivider()
                        SettingsRow(
                            label = "Add apps",
                            supporting = "Choose which apps may forward alerts",
                            onClick = { picking = true },
                            showChevron = false,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Recent") }
        if (state.recent.isEmpty()) {
            item { EmptyState("Nothing forwarded yet.") }
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

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = GraftPalette.surface, shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(GraftSpacing.l)) {
                Text("Forward these apps", style = MaterialTheme.typography.titleLarge)
                androidx.compose.foundation.layout.Spacer(Modifier.size(GraftSpacing.s))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(GraftSpacing.s))
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
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
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
            androidx.compose.material3.Icon(
                imageVector = GraftIcons.Alerts,
                contentDescription = null,
                tint = if (granted) GraftPalette.connected else GraftPalette.warning,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (granted) "Notification access allowed" else "Notification access is off",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (granted) {
                        "Tick the apps whose alerts should reach your Mac."
                    } else {
                        "Tap to allow, then choose apps here."
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

/** The app's own launcher icon, loaded off the main thread. */
@Composable
private fun AppIcon(packageName: String, size: Int = 28) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
            }.getOrNull()
        }
    }
    icon?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size.dp))
    }
}
