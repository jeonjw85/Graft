package kr.jjw.graft.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.notifications.NotificationAllowList
import kr.jjw.graft.notifications.PostedNotification
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.ui.components.EmptyState
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.service.installedApps
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
}

@Composable
fun AlertsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by ConnectionRepository.state.collectAsState()
    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        NotificationAllowList.refresh(context)
        allowed = NotificationAllowList.load(context)
        installed = installedApps(context)
        accessGranted = NotificationAccess.isGranted(context)
    }

    val state = AlertsScreenRules.from(accessGranted, connection.recentNotifications)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GraftSpacing.l),
        verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs),
    ) {
        item {
            NotificationAccessSection(
                granted = state.accessGranted,
                onOpenSettings = { NotificationAccess.openSettings(context) },
            )
        }

        item { SectionHeader("Allowed apps") }

        if (installed.isEmpty()) {
            item { EmptyState("No notification access yet. Grant it above to choose apps.") }
        } else {
            allowListRows(
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
            )
        }

        item { SectionHeader("Recent alerts") }

        if (state.recent.isEmpty()) {
            item { EmptyState("No apps allowed yet. Tick an app to forward its notifications.") }
        } else {
            items(state.recent, key = { "${it.pkg}-${it.postedAt}" }) { notification ->
                Column {
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
    }
}

@Composable
private fun NotificationAccessSection(granted: Boolean, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GraftSpacing.s)) {
        Text("Phone notifications", style = MaterialTheme.typography.titleMedium)
        Text(
            if (granted) {
                "Graft can forward the apps you tick. Nothing else leaves the phone."
            } else {
                "Grant notification access so phone alerts can appear on your Mac."
            },
            style = MaterialTheme.typography.bodySmall,
            color = GraftPalette.onSurfaceVariant,
        )
        androidx.compose.material3.Button(onClick = onOpenSettings) {
            Text(if (granted) "Notification access settings" else "Grant notification access")
        }
    }
}

private fun LazyListScope.allowListRows(
    installed: List<Pair<String, String>>,
    allowed: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    items(installed, key = { it.first }) { (pkg, label) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allowed.contains(pkg),
                onCheckedChange = { onToggle(pkg, it) },
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = GraftSpacing.xs),
            )
        }
    }
}
