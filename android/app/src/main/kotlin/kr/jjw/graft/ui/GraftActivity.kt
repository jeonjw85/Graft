package kr.jjw.graft.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.ui.main.AppTab
import kr.jjw.graft.ui.theme.GraftTheme

class GraftActivity : ComponentActivity() {
    private val requestedTab = mutableStateOf<AppTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        setContent {
            GraftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        requestedTab = requestedTab.value,
                        onTabHandled = { requestedTab.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val payload = ShareIntentParser.parse(intent) ?: return
        if (!ConnectionService.isPaired(this)) return

        when (payload) {
            is SharedPayload.Files -> {
                ConnectionService.sendFiles(this, payload.uris)
                requestedTab.value = AppTab.Files
            }

            is SharedPayload.Text -> {
                ConnectionService.sendSharedText(this, payload.text)
                requestedTab.value = AppTab.Connection
            }
        }
    }
}
