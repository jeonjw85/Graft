package kr.jjw.graft.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.ui.QRScanner
import kr.jjw.graft.ui.ScanViewModel
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/**
 * The pairing surface: camera first, typed values underneath.
 *
 * Used by both onboarding and the "Pair another phone" dialog, so a returning user sees exactly
 * what a first-time user sees.
 */
@Composable
fun PairingContent(
    onPermissionDenied: () -> Unit,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scan = remember { ScanViewModel() }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GraftSpacing.s)) {
        Text(
            "Scan the QR code in the Graft menu bar, or type the values below.",
            style = MaterialTheme.typography.bodyMedium,
            color = GraftPalette.onSurfaceVariant,
        )

        // The scanner keeps its own permission states (ask, blocked, camera unavailable) and shows
        // the matching prompt in place. Hiding it on denial loses the only path back to Settings.
        QRScanner(
            onScanned = { raw ->
                scan.onQRScanned(raw)
                val offer = scan.state.offer
                if (offer == null) {
                    error = scan.state.error
                } else {
                    error = null
                    ConnectionService.savePairingOffer(context, offer)
                    ConnectionService.start(context)
                    onPaired()
                }
            },
            onPermissionDenied = onPermissionDenied,
        )

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = GraftPalette.error)
        }

        ManualPairingForm(
            context = context,
            onPaired = {
                ConnectionService.start(context)
                onPaired()
            },
        )
    }
}

/** The same pairing surface in a window, for adding another phone later. */
@Composable
fun PairingDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = GraftPalette.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(GraftSpacing.l),
            ) {
                Text("Pair a phone", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(GraftSpacing.s))
                PairingContent(
                    onPermissionDenied = { },
                    onPaired = onDismiss,
                )
                Spacer(Modifier.height(GraftSpacing.s))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
