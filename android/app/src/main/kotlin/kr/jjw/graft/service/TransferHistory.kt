package kr.jjw.graft.service

import kr.jjw.graft.core.TransferDirection
import kr.jjw.graft.files.TransferFailureKind

/** How many finished transfers the app keeps, in memory and on disk. */
const val TRANSFER_HISTORY_LIMIT = 50

enum class TransferOutcome {
    Completed,
    Failed,
}

/** One finished transfer, as the Files tab shows it. */
data class TransferRecord(
    val id: UInt,
    val name: String,
    val bytes: Long,
    val direction: TransferDirection,
    val outcome: TransferOutcome,
    val reason: String,
    val destination: String?,
    val finishedAtMillis: Long,
) {
    /** A receive whose file another app can open; an app-private path cannot be handed over. */
    val canOpen: Boolean get() = destination != null && destination.startsWith("content://")
}

/**
 * The recent transfers, newest first.
 *
 * In memory on purpose: persisting it would need a schema, and the files themselves are the durable
 * record. A repeated transfer id replaces its earlier row so a retry does not read as two transfers.
 */
class TransferHistory(
    private val capacity: Int = TRANSFER_HISTORY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rows = mutableListOf<TransferRecord>()

    val list: List<TransferRecord> get() = rows.toList()

    @Synchronized
    fun record(
        id: UInt,
        name: String,
        bytes: Long,
        direction: TransferDirection,
        error: TransferFailureKind?,
        destination: String?,
    ): TransferRecord {
        // Ids restart with every session, so a retry is the same id *and* the same file in the same
        // direction; a different file reusing the id keeps its own row.
        rows.removeAll { it.id == id && it.name == name && it.direction == direction }

        val row = TransferRecord(
            id = id,
            name = name,
            bytes = bytes,
            direction = direction,
            outcome = if (error == null) TransferOutcome.Completed else TransferOutcome.Failed,
            reason = messageFor(error),
            destination = destination,
            finishedAtMillis = clock(),
        )
        rows.add(0, row)
        while (rows.size > capacity) rows.removeAt(rows.lastIndex)
        return row
    }

    @Synchronized
    fun clear() {
        rows.clear()
    }

    companion object {
        /** What a person reads instead of an enum name. */
        fun messageFor(kind: TransferFailureKind?): String = when (kind) {
            null -> "Completed"
            TransferFailureKind.HASH_MISMATCH -> "Hash mismatch"
            TransferFailureKind.WRONG_OFFSET -> "Interrupted"
            TransferFailureKind.READ_FAILED -> "Read failed"
            TransferFailureKind.REJECTED -> "Rejected"
            TransferFailureKind.UNPAIRED_PEER -> "Not paired"
            TransferFailureKind.DUPLICATE_TRANSFER -> "Already in progress"
            TransferFailureKind.UNSAFE_NAME -> "Name not allowed"
        }
    }
}
