package kr.jjw.graft.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.jjw.graft.ui.onboarding.OnboardingFlow
import kr.jjw.graft.ui.theme.GraftTheme

/**
 * Temporary host until the tabbed shell lands: onboarding, transfers, and notification access.
 *
 * Task 4 of the UI refresh replaces this with `AppRoot`.
 */
class GraftActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GraftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        OnboardingFlow(onFinished = { })
                        TransferList(
                            transfers = remember { mutableStateMapOf<UInt, kr.jjw.graft.files.TransferProgress>() },
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        NotificationAccessScreen(modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}
