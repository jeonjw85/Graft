package kr.jjw.graft.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Camera preview that decodes the pairing QR code.
 *
 * The camera is bound only while this composable is in composition and unbound on dispose, so
 * leaving the pairing screen does not hold the camera open.
 */
private fun Context.findActivity(): android.app.Activity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun QRScanner(
    onScanned: (String) -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var bindError by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(false) }
    var canAskAgain by remember { mutableStateOf(true) }

    val activity = remember(context) { context.findActivity() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        asked = true
        granted = allowed
        canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true
        if (!allowed) onPermissionDenied()
    }

    // Returning from Settings should re-check the grant without leaving the screen.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    val state = PermissionGuidance.cameraState(
        granted = granted,
        asked = asked,
        canAskAgain = canAskAgain,
    )

    // The scan step is only shown when the user reached it, so asking on entry is expected.
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (state != CameraPermissionState.Granted) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state == CameraPermissionState.Blocked) {
                    "Camera access is blocked. Allow it in Settings to scan the QR code, or type " +
                        "the values below."
                } else {
                    "Allow camera access to scan the QR code, or use the values below."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state == CameraPermissionState.Askable) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            } else {
                Button(onClick = { context.openAppSettings() }) {
                    Text("Open settings")
                }
            }
        }
        return
    }

    val analyzer = remember { QRAnalyzer(onResult = onScanned) }
    val previewView = remember { PreviewView(context) }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
    )

    DisposableEffect(previewView, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                bindError = null
            } catch (error: Exception) {
                // No camera or the lens is in use: fall back to the typed values.
                analyzer.enabled = false
                bindError = "The camera is unavailable. Use the values below."
                onPermissionDenied()
            }
        }, executor)

        onDispose {
            analyzer.enabled = false
            provider?.unbindAll()
        }
    }

    bindError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
