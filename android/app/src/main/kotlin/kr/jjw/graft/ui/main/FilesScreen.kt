package kr.jjw.graft.ui.main

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferProgress
import kr.jjw.graft.service.ConnectionRepository
import kr.jjw.graft.service.ConnectionService
import kr.jjw.graft.service.TRANSFER_HISTORY_LIMIT
import kr.jjw.graft.service.TransferOutcome
import kr.jjw.graft.service.TransferRecord
import kr.jjw.graft.ui.components.EmptyState
import kr.jjw.graft.ui.components.SectionHeader
import kr.jjw.graft.ui.components.SettingsGroup
import kr.jjw.graft.ui.theme.GraftPalette
import kr.jjw.graft.ui.theme.GraftSpacing

/** An in-flight transfer together with the id the protocol assigned it. */
data class ActiveTransfer(val id: UInt, val progress: TransferProgress)

data class FilesScreenState(
    val active: List<ActiveTransfer>,
    val recent: List<TransferRecord>,
)

/** Pure shaping of repository state into what the Files tab draws. */
object FilesScreenRules {
    val MAX_RECENT = TRANSFER_HISTORY_LIMIT

    fun from(
        transfers: Map<UInt, TransferProgress>,
        history: List<TransferRecord>,
    ): FilesScreenState {
        // Match on the whole transfer, not the id: ids are only unique per session.
        val finished = history.map { Triple(it.id, it.name, it.direction) }.toSet()
        return FilesScreenState(
            active = transfers.filter { (id, progress) ->
                Triple(id, progress.name, progress.direction) !in finished
            }
                .entries
                .sortedBy { it.key }
                .map { ActiveTransfer(it.key, it.value) },
            recent = history.take(MAX_RECENT),
        )
    }

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

    fun canOpen(destination: String?): Boolean =
        destination != null && destination.startsWith("content://")

    /** A short label for the file tile, taken from the extension. */
    fun badgeFor(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.isEmpty() || extension.length > 4) "FILE" else extension.uppercase()
    }

    fun relativeTime(nowMillis: Long, thenMillis: Long): String {
        val delta = (nowMillis - thenMillis).coerceAtLeast(0)
        val minutes = delta / 60_000
        val hours = delta / 3_600_000
        val days = delta / 86_400_000
        return when {
            delta < 60_000 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours h ago"
            else -> "$days d ago"
        }
    }

    fun detail(record: TransferRecord, nowMillis: Long = System.currentTimeMillis()): String {
        val parts = mutableListOf<String>()
        if (record.outcome == TransferOutcome.Failed) parts += record.reason
        parts += if (record.direction == TransferDirection.receive) "Received" else "Sent"
        parts += formatSize(record.bytes)
        parts += relativeTime(nowMillis, record.finishedAtMillis)
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

    // Ages are read from this clock, so a row left on screen does not keep saying "just now".
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { ConnectionService.sendFile(context, it) } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = GraftSpacing.l),
            verticalArrangement = Arrangement.spacedBy(GraftSpacing.s),
            contentPadding = PaddingValues(
                top = GraftSpacing.xl,
                bottom = GraftSpacing.xl,
            ),
        ) {
            item {
                Text("Files", style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Button(
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send a file to Mac")
                }
            }
            state.outgoingError?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = GraftPalette.error,
                    )
                }
            }

            if (screen.active.isNotEmpty()) {
                item { SectionHeader("In progress") }
                items(screen.active, key = { "active-${it.id}" }) { active ->
                    SettingsGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(GraftSpacing.l),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
                        ) {
                            FileTile(label = FilesScreenRules.badgeFor(active.progress.name))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    active.progress.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(GraftSpacing.xs))
                                LinearProgressIndicator(
                                    progress = { active.progress.fraction.toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    if (active.progress.total == 0L) {
                                        "Finishing…"
                                    } else {
                                        "${FilesScreenRules.percent(active.progress)}%  ·  " +
                                            FilesScreenRules.formatSize(active.progress.total)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraftPalette.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Recent") }
            if (screen.recent.isEmpty()) {
                item { EmptyState("No files yet. Files you send or receive appear here.") }
            } else {
                items(screen.recent, key = { it.id.toString() }) { record ->
                    val openable = FilesScreenRules.canOpen(record.destination)
                    SettingsGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(GraftSpacing.l),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(GraftSpacing.m),
                        ) {
                            FileTile(label = FilesScreenRules.badgeFor(record.name))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    record.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    FilesScreenRules.detail(record, nowMillis),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (record.outcome == TransferOutcome.Failed) {
                                        GraftPalette.error
                                    } else {
                                        GraftPalette.onSurfaceVariant
                                    },
                                )
                            }
                            if (openable) {
                                TextButton(
                                    onClick = {
                                        val opened = runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(
                                                        android.net.Uri.parse(record.destination),
                                                        "*/*",
                                                    )
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            )
                                        }
                                        if (opened.isFailure) {
                                            scope.launch {
                                                snackbar.showSnackbar("No app can open this file")
                                            }
                                        }
                                    }
                                ) { Text("Open") }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** A coloured tile with the extension, so a list of files is scannable without artwork. */
@Composable
private fun FileTile(label: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(GraftPalette.surface, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = GraftPalette.primary,
        )
    }
}
