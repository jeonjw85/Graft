package kr.jjw.graft.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import kr.jjw.graft.R
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
                        TextButton(onClick = { refresh { viewModel.advance() } }) {
                            Text(stringResource(R.string.action_skip))
                        }
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
                val primaryRes = primaryActionLabelRes(state.step)
                if (primaryRes != null) {
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
                        Text(stringResource(primaryRes))
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

private fun primaryActionLabelRes(step: OnboardingStep): Int? = when (step) {
    OnboardingStep.Welcome -> R.string.action_get_started
    OnboardingStep.Permissions -> R.string.action_continue
    OnboardingStep.Scan, OnboardingStep.Done -> null
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(GraftSpacing.xl))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.onboarding_welcome_desc),
        style = MaterialTheme.typography.bodyLarge,
        color = GraftPalette.onSurfaceVariant,
    )
}

private fun canPostNotifications(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsStep(context: android.content.Context) {
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    var canPost by remember { mutableStateOf(canPostNotifications(context)) }
    val askPostNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { canPost = canPostNotifications(context) }

    // The user leaves for system settings; refresh the answer when they come back.
    LifecycleResumeEffect(Unit) {
        granted = NotificationAccess.isGranted(context)
        canPost = canPostNotifications(context)
        onPauseOrDispose { }
    }

    Spacer(Modifier.height(GraftSpacing.s))
    Text(stringResource(R.string.settings_section_notifications), style = MaterialTheme.typography.titleMedium)
    Text(
        if (granted) {
            stringResource(R.string.onboarding_notifications_granted)
        } else {
            stringResource(R.string.onboarding_notifications_prompt)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = GraftPalette.onSurfaceVariant,
    )
    if (!granted) {
        Button(onClick = { NotificationAccess.openSettings(context) }) {
            Text(stringResource(R.string.action_open_notification_access))
        }
    }

    // Android 13+ hides the ongoing "connected" notice without this, which makes the background
    // link invisible. Ask here, where the user is already granting permissions.
    if (!canPost) {
        Spacer(Modifier.height(GraftSpacing.s))
        Text(stringResource(R.string.onboarding_background_notice), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.onboarding_background_notice_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = GraftPalette.onSurfaceVariant,
        )
        Button(onClick = { askPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text(stringResource(R.string.action_allow_notifications))
        }
    }
}
