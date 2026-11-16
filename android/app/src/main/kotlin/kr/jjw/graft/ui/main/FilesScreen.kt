package kr.jjw.graft.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.service.TransferRecord
import kr.jjw.graft.ui.components.EmptyState
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.TransferRow
import kr.jjw.graft.ui.theme.GraftSpacing

/** An in-flight transfer together with the id the protocol assigned it. */
data class ActiveTransfer(val id: UInt, val progress: TransferProgress)

data class FilesScreenState(
    val active: List<ActiveTransfer>,
    val recent: List<TransferRecord>,
)

/** Pure shaping of repository state into what the Files tab draws. */
object FilesScreenRules {
    const val MAX_RECENT = 50

    fun from(
        transfers: Map<UInt, TransferProgress>,
        history: List<TransferRecord>,
    ): FilesScreenState {
        val finished = history.map { it.id }.toSet()
        return FilesScreenState(
            // A finished transfer is represented by its history row, not by a stuck progress row.
            active = transfers
                .filterKeys { it !in finished }
                .entries
                .sortedBy { it.key }
                .map { ActiveTransfer(it.key, it.value) },
            recent = history.take(MAX_RECENT),
        )
    }

    /** Whole percent, clamped; an empty file that completed reads 100 %. */
    fun percent(progress: TransferProgress): Int {
        if (progress.total == 0L) return 100
        return (progress.fraction.coerceIn(0.0, 1.0) * 100).toInt()
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit += 1
        }
        return "%.1f %s".format(value, units[unit])
    }

    /** Only a content URI can be handed to another app; an app-private path cannot. */
    fun canOpen(destination: String?): Boolean =
        destination != null && destination.startsWith("content://")

    fun detail(record: TransferRecord): String {
        val parts = mutableListOf<String>()
        if (record.outcome == kr.jjw.graft.service.TransferOutcome.Failed) parts += record.reason
        parts += if (record.direction == TransferDirection.receive) "Received" else "Sent"
        parts += formatSize(record.bytes)
        val base = parts.joinToString("  ·  ")
        return if (record.destination != null && !canOpen(record.destination)) {
            "$base  ·  Saved to app storage"
        } else {
            base
        }
    }
}

@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by ConnectionRepository.state.collectAsState()
    val screen = FilesScreenRules.from(state.transfers, state.history)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { ConnectionService.sendFile(context, it) } }

    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = GraftSpacing.l, vertical = GraftSpacing.m),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.s),
        ) {
            Button(onClick = { pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Send a file to Mac")
            }
            state.outgoingError?.let {
                Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = kr.jjw.graft.ui.theme.GraftPalette.error)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = GraftSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.xs),
        ) {
            if (screen.active.isNotEmpty()) {
                item { SectionHeader("In progress") }
                items(screen.active, key = { it.id }) { active ->
                    TransferRow(
                        name = active.progress.name,
                        detail = "${FilesScreenRules.percent(active.progress)}%",
                        progress = active.progress.fraction.toFloat(),
                    )
                }
            }

            if (screen.recent.isEmpty() && screen.active.isEmpty()) {
                item {
                    EmptyState("No files yet. Files you send or receive appear here.")
                }
            } else if (screen.recent.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(screen.recent, key = { it.id }) { record ->
                    val openable = FilesScreenRules.canOpen(record.destination)
                    TransferRow(
                        name = record.name,
                        detail = FilesScreenRules.detail(record),
                        progress = null,
                        onOpen = if (openable) {
                            {
                                val opened = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(android.net.Uri.parse(record.destination), "*/*")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                }
                                if (opened.isFailure) {
                                    scope.launch { snackbar.showSnackbar("No app can open this file") }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }
}
