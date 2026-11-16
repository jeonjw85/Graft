package kr.jjw.graft.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.jjw.graft.notifications.NotificationAllowList

/**
 * Lets the user pick which apps may forward notifications.
 *
 * The list starts empty and stays empty until the user chooses, so a fresh install forwards
 * nothing. Notification access itself is granted in system settings; this screen explains that and
 * links to it.
 */
@Composable
fun NotificationAccessScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        NotificationAllowList.refresh(context)
        allowed = NotificationAllowList.load(context)
        installed = loadInstalledApps(context)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Phone notifications", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Graft forwards only the apps you choose. Nothing is sent until you pick one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) {
            Text("Open notification access settings")
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn {
            items(installed) { (pkg, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allowed.contains(pkg),
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
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** Installed launchable apps, sorted by label. */
private fun loadInstalledApps(context: Context): List<Pair<String, String>> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(intent, 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            pkg to (info.loadLabel(manager)?.toString() ?: pkg)
        }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
