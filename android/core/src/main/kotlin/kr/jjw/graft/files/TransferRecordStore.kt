package kr.jjw.graft.files

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A transfer that can still be resumed. */
@Serializable
data class ResumeRecord(
    val name: String,
    val directory: String,
    val size: Long,
    val bytesConfirmed: Long,
    val updatedAtMillis: Long,
)

/**
 * Remembers partial transfers so a restart can continue instead of starting over.
 *
 * A record matches only when the name, directory, and total size agree and the bytes on disk equal
 * `bytesConfirmed`. A record older than the TTL is deleted, not just ignored, so the file does not
 * grow forever.
 */
class TransferRecordStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun find(
        name: String,
        directory: String,
        size: Long,
        partialBytesOnDisk: Long,
    ): ResumeRecord? {
        val records = load()
        val record = records.firstOrNull {
            it.name == name && it.directory == directory && it.size == size
        } ?: return null

        val age = clock() - record.updatedAtMillis
        if (age >= ttlMillis) {
            remove(name = name, directory = directory)
            return null
        }
        if (record.bytesConfirmed != partialBytesOnDisk || partialBytesOnDisk <= 0) {
            return null
        }
        return record
    }

    @Synchronized
    fun upsert(record: ResumeRecord) {
        val records = load().filterNot {
            it.name == record.name && it.directory == record.directory
        }.toMutableList()
        records.add(record)
        save(records)
    }

    @Synchronized
    fun remove(name: String, directory: String) {
        save(load().filterNot { it.name == name && it.directory == directory })
    }

    /** Deletes every record past the TTL and returns how many were dropped. */
    @Synchronized
    fun purgeExpired(): Int {
        val now = clock()
        val records = load()
        val kept = records.filter { now - it.updatedAtMillis < ttlMillis }
        if (kept.size != records.size) save(kept)
        return records.size - kept.size
    }

    /** Number of stored records, for tests and diagnostics. */
    @Synchronized
    fun count(): Int = load().size

    private fun load(): List<ResumeRecord> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<StoreFile>(text).records
        }.getOrDefault(emptyList())
    }

    private fun save(records: List<ResumeRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(StoreFile(records)))
    }

    @Serializable
    private data class StoreFile(val records: List<ResumeRecord> = emptyList())

    companion object {
        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
