package kr.jjw.graft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.service.ConnectionStatus
import kr.jjw.graft.ui.main.MainScaffold
import kr.jjw.graft.ui.onboarding.OnboardingFlow

/**
 * Chooses between preparation and the main screen.
 *
 * The pin is the only durable signal, so a paired user never sees onboarding again, and forgetting
 * the Mac returns to onboarding without restarting the app.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var paired by remember { mutableStateOf(ConnectionService.isPaired(context)) }
    val connection by ConnectionRepository.state.collectAsState()

    // The transfer list is useful even while disconnected, so it loads with the app, not the service.
    LaunchedEffect(Unit) {
        val store = kr.jjw.graft.service.TransferHistoryStore(
            java.io.File(context.filesDir, "transfer-history.json")
        )
        withContext(Dispatchers.IO) { ConnectionRepository.attachHistoryStore(store) }
    }

    LaunchedEffect(connection.status) {
        if (connection.status is ConnectionStatus.Connected) paired = true
    }

    // A paired app reconnects on launch, and a saved offer resumes: making the user press
    // Reconnect is not a feature.
    LaunchedEffect(paired) {
        if (ConnectionService.shouldConnect(context)) ConnectionService.start(context)
    }

    if (AppEntry.startDestination(paired) == StartDestination.Onboarding) {
        OnboardingFlow(onFinished = { paired = true })
    } else {
        MainScaffold(paired = paired, onForget = { paired = false })
    }
}
