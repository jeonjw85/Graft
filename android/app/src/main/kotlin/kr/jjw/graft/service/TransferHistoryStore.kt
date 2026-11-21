package kr.jjw.graft.service

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.jjw.graft.core.TransferDirection

@Serializable
private data class StoredRecord(
    val id: UInt,
    val name: String,
    val bytes: Long,
    val direction: String,
    val outcome: String,
    val reason: String,
    val destination: String? = null,
    val finishedAtMillis: Long,
)

/**
 * Keeps the recent transfers across app restarts.
 *
 * The received files are the durable record, but the list is what tells a user where a file went and
 * whether it arrived intact. A corrupt or unreadable file loads as empty instead of failing the app.
 */
class TransferHistoryStore(
    private val file: File,
    private val capacity: Int = TRANSFER_HISTORY_LIMIT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<TransferRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredRecord>>(file.readText())
                .take(capacity)
                .map { it.toRecord() }
        }.getOrDefault(emptyList())
    }

    fun save(records: List<TransferRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(records.take(capacity).map { it.toStored() }))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun StoredRecord.toRecord() = TransferRecord(
        id = id,
        name = name,
        bytes = bytes,
        direction = if (direction == TransferDirection.send.name) TransferDirection.send else TransferDirection.receive,
        outcome = if (outcome == TransferOutcome.Failed.name) TransferOutcome.Failed else TransferOutcome.Completed,
        reason = reason,
        destination = destination,
        finishedAtMillis = finishedAtMillis,
    )

    private fun TransferRecord.toStored() = StoredRecord(
        id = id,
        name = name,
        bytes = bytes,
        direction = direction.name,
        outcome = outcome.name,
        reason = reason,
        destination = destination,
        finishedAtMillis = finishedAtMillis,
    )
}
