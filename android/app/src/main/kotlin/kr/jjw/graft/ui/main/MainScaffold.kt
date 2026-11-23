package kr.jjw.graft.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kr.jjw.graft.R
import kr.jjw.graft.ui.theme.GraftIcons

/** The three jobs of the app: the link, the files, the alerts. */
enum class AppTab(val label: String, val labelRes: Int, val icon: ImageVector) {
    Connection("Connection", R.string.tab_connection, GraftIcons.Link),
    Files("Files", R.string.tab_files, GraftIcons.Files),
    Alerts("Alerts", R.string.tab_alerts, GraftIcons.Alerts),
}

object AppTabRules {
    /** Back returns to the first tab; on the first tab it returns null so the system exits. */
    fun onBack(current: AppTab): AppTab? =
        if (current == AppTab.Connection) null else AppTab.Connection
}

@Composable
fun MainScaffold(
    paired: Boolean,
    onForget: () -> Unit,
    requestedTab: AppTab? = null,
    onTabHandled: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(requestedTab ?: AppTab.Connection) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            settingsOpen = false
            onTabHandled()
        }
    }

    // Back leaves the app from the first tab; from another tab it returns to the first.
    BackHandler(enabled = AppTabRules.onBack(tab) != null) {
        AppTabRules.onBack(tab)?.let { tab = it }
    }

    if (settingsOpen) {
        // Back must close settings, not leave the app.
        BackHandler(enabled = true) { settingsOpen = false }
        SettingsScreen(onClose = { settingsOpen = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    val title = stringResource(entry.labelRes)
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = title) },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            AppTab.Connection -> ConnectScreen(
                paired = paired,
                onForget = onForget,
                onOpenSettings = { settingsOpen = true },
                onSendFile = { tab = AppTab.Files },
                modifier = content,
            )
            AppTab.Files -> FilesScreen(modifier = content)
            AppTab.Alerts -> AlertsScreen(modifier = content)
        }
    }
}
