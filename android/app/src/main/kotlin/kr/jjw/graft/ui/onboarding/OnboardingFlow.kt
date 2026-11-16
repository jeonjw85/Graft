package kr.jjw.graft.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kr.jjw.graft.notifications.NotificationAccess
import kr.jjw.graft.ui.OnboardingStep
import kr.jjw.graft.ui.OnboardingViewModel
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The counter shown in the top bar during onboarding. */
object OnboardingProgress {
    fun label(step: OnboardingStep): String = when (step) {
        OnboardingStep.Welcome -> "1 of 3"
        OnboardingStep.Permissions -> "2 of 3"
        OnboardingStep.Scan -> "3 of 3"
        OnboardingStep.Done -> ""
    }
}

/**
 * The three preparation steps, each with its own scroll and a pinned primary button.
 *
 * Pairing can finish at any time (the phone scans and the service connects), so the caller decides
 * what "finished" means; this screen only reports it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val viewModel = remember { OnboardingViewModel() }
    var state by remember { mutableStateOf(viewModel.state) }

    fun refresh(action: () -> Unit) {
        action()
        state = viewModel.state
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(OnboardingProgress.label(state.step)) },
                actions = {
                    if (state.step != OnboardingStep.Scan) {
                        TextButton(onClick = { refresh { viewModel.advance() } }) { Text("Skip") }
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.padding(GraftSpacing.l)) {
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GraftPalette.error,
                        modifier = Modifier.padding(bottom = GraftSpacing.s),
                    )
                }
                val primary = primaryActionLabel(state.step)
                if (primary != null) {
                    Button(
                        onClick = {
                            when (state.step) {
                                OnboardingStep.Welcome -> refresh { viewModel.advance() }
                                OnboardingStep.Permissions -> refresh {
                                    viewModel.onPermissionResult(
                                        "notifications",
                                        NotificationAccess.isGranted(context),
                                    )
                                    viewModel.advance()
                                }
                                else -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(primary)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = GraftSpacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.m),
        ) {
            when (state.step) {
                OnboardingStep.Welcome -> WelcomeStep()
                OnboardingStep.Permissions -> PermissionsStep(context = context)
                OnboardingStep.Scan -> PairingContent(
                    onPermissionDenied = {
                        refresh { viewModel.onPermissionResult("camera", false) }
                    },
                    onPaired = {
                        refresh { viewModel.advance() }
                        onFinished()
                    },
                )
                OnboardingStep.Done -> Unit
            }
        }
    }
}

private fun primaryActionLabel(step: OnboardingStep): String? = when (step) {
    OnboardingStep.Welcome -> "Get started"
    OnboardingStep.Permissions -> "Continue"
    OnboardingStep.Scan, OnboardingStep.Done -> null
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(GraftSpacing.xl))
    Text("Graft", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Connect this phone to your Mac over the same Wi-Fi. No account, no cloud.",
        style = MaterialTheme.typography.bodyLarge,
        color = GraftPalette.onSurfaceVariant,
    )
}

@Composable
private fun PermissionsStep(context: android.content.Context) {
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }

    // The user leaves for system settings; refresh the answer when they come back.
    LifecycleResumeEffect(Unit) {
        granted = NotificationAccess.isGranted(context)
        onPauseOrDispose { }
    }

    Spacer(Modifier.height(GraftSpacing.s))
    Text("Notifications", style = MaterialTheme.typography.titleMedium)
    Text(
        if (granted) {
            "Notification access is on. Pick which apps to forward later in the Alerts tab."
        } else {
            "Grant notification access to show phone alerts on your Mac. Everything else works " +
                "without it."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = GraftPalette.onSurfaceVariant,
    )
    if (!granted) {
        Button(onClick = { NotificationAccess.openSettings(context) }) {
            Text("Open notification access")
        }
    }
}
