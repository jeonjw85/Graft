package kr.jjw.graft.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kr.jjw.graft.ui.theme.GraftIcons

/** The three jobs of the app: the link, the files, the alerts. */
enum class AppTab(val label: String, val icon: ImageVector) {
    Connection("Connection", GraftIcons.Link),
    Files("Files", GraftIcons.Files),
    Alerts("Alerts", GraftIcons.Alerts),
}

object AppTabRules {
    /** Back returns to the first tab; on the first tab it returns null so the system exits. */
    fun onBack(current: AppTab): AppTab? =
        if (current == AppTab.Connection) null else AppTab.Connection
}

@Composable
fun MainScaffold(paired: Boolean, onForget: () -> Unit) {
    var tab by remember { mutableStateOf(AppTab.Connection) }

    // Back leaves the app from the first tab; from another tab it returns to the first.
    BackHandler(enabled = AppTabRules.onBack(tab) != null) {
        AppTabRules.onBack(tab)?.let { tab = it }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
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
                modifier = content,
            )
            AppTab.Files -> FilesScreen(modifier = content)
            AppTab.Alerts -> AlertsScreen(modifier = content)
        }
    }
}
