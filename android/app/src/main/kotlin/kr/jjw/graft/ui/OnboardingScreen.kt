package kr.jjw.graft.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class GraftActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        OnboardingScreen(OnboardingViewModel())
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

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    var state by remember { mutableStateOf(viewModel.state) }

    fun refresh(action: () -> Unit) {
        action()
        state = viewModel.state
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (state.step) {
            OnboardingStep.Welcome -> {
                Text("Graft", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Connect this phone to your Mac over the same Wi-Fi. " +
                        "No account, no cloud."
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { refresh { viewModel.advance() } }) {
                    Text("Get started")
                }
            }

            OnboardingStep.Permissions -> {
                Text("Permissions", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.notificationAccessGranted) {
                        "Notification access granted."
                    } else {
                        "Grant notification access so phone alerts can appear on your Mac."
                    }
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        refresh { viewModel.onPermissionResult("notifications", true) }
                    }
                ) {
                    Text("Grant notification access")
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { refresh { viewModel.advance() } }) {
                    Text("Continue")
                }
            }

            OnboardingStep.Scan -> {
                Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.useManualCode) {
                        "Camera unavailable. Enter the six-digit code shown on your Mac."
                    } else {
                        "Point the camera at the QR code in the Graft menu bar."
                    }
                )
                Spacer(Modifier.height(16.dp))
                var code by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Six-digit code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { refresh { viewModel.onCodeEntered(code) } }) {
                    Text("Use this code")
                }
                if (state.cameraGranted) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { refresh { viewModel.advance() } }) {
                        Text("Skip for now")
                    }
                }
            }

            OnboardingStep.Done -> {
                val context = LocalContext.current
                val connection by ConnectionRepository.state.collectAsState()

                Text("Ready", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    when (val status = connection.status) {
                        is kr.jjw.graft.service.ConnectionStatus.Connected ->
                            "Connected to ${connection.peerName ?: "your Mac"}"
                        is kr.jjw.graft.service.ConnectionStatus.Connecting ->
                            connection.lastError ?: "Connecting…"
                        is kr.jjw.graft.service.ConnectionStatus.Failed -> status.message
                        kr.jjw.graft.service.ConnectionStatus.Idle -> "Not connected"
                    }
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { ConnectionService.start(context) }) {
                    Text("Keep connected in the background")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { ConnectionService.stop(context) }) {
                    Text("Disconnect")
                }

                val derived = ConnectedScreenState.from(connection)
                if (derived.canSendClipboard) {
                    Spacer(Modifier.height(16.dp))
                    val clipboardManager = androidx.compose.ui.platform.LocalContext.current
                        .getSystemService(android.content.ClipboardManager::class.java)
                    Button(
                        onClick = {
                            val text = clipboardManager.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            if (!text.isNullOrEmpty()) {
                                ConnectionService.sendClipboard(text)
                            }
                        }
                    ) {
                        Text("Send clipboard to Mac")
                    }
                    derived.clipboardNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    ConnectionRepository.state.value.lastClipboard?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Last shared: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        state.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
