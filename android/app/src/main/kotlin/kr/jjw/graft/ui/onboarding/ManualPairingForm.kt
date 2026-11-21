package kr.jjw.graft.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.ui.ManualPairingRules

/** Code + fingerprint prefix + address, for phones with no camera or a blocked camera. */
@Composable
fun ManualPairingForm(
    context: Context,
    onPaired: () -> Unit = {},
) {
    var code by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47800") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Pair without a camera", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter the three values shown on your Mac's menu bar window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Six-digit code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("Fingerprint (8 characters)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Mac IP address") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                when (val result = ManualPairingRules.validate(code, prefix, host, port)) {
                    is ManualPairingRules.Result.Invalid -> message = result.message
                    is ManualPairingRules.Result.Valid -> {
                        message = null
                        ConnectionService.saveManualPairing(
                            context = context,
                            host = result.pairing.host,
                            port = result.pairing.port,
                            prefix = result.pairing.prefix,
                            token = result.pairing.code,
                        )
                        onPaired()
                    }
                }
            }
        ) {
            Text("Pair")
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

