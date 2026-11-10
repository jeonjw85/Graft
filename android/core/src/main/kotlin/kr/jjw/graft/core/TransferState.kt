package kr.jjw.graft.core

enum class TransferPhase {
    idle, offered, accepted, sending, receiving, verifying, completed, failed, cancelled
}

enum class TransferDirection {
    send, receive
}

data class FileOffer(
    val transferId: UInt,
    val name: String,
    val size: ULong,
    val mime: String,
)

data class TransferRecord(
    val transferId: UInt,
    val phase: TransferPhase,
    val name: String,
    val size: ULong,
    val bytesConfirmed: ULong,
    val sha256: String?,
    val direction: TransferDirection,
    val startedAt: Long,
)

sealed interface TransferEvent {
    data object Offer : TransferEvent
    data class Accept(val offset: ULong) : TransferEvent
    data object Reject : TransferEvent
    data class Chunk(val offset: ULong, val length: Int) : TransferEvent
    data object Complete : TransferEvent
    data object HashOk : TransferEvent
    data object HashMismatch : TransferEvent
    data object Stall : TransferEvent
    data object Cancel : TransferEvent
}

sealed interface TransferAction {
    data object Abort : TransferAction
    data object ComputeHash : TransferAction
    data object CommitFile : TransferAction
    data object DeletePartial : TransferAction
    data class SeekTo(val offset: ULong) : TransferAction
}

/**
 * The transfer state machine shared (by fixture) with the Swift implementation.
 *
 * A chunk is accepted only when its offset equals `bytesConfirmed`. Anything else would write a
 * hole or duplicate bytes and still leave a file of the right length.
 */
class TransferMachine(
    record: TransferRecord,
    private var lastActivity: Long = record.startedAt,
) {
    var record: TransferRecord = record
        private set

    val phase: TransferPhase get() = record.phase

    /** Test hook for driving fixture rows from an arbitrary phase. */
    fun forcePhase(phase: TransferPhase) {
        record = record.copy(phase = phase)
    }

    fun tick(now: Long): List<TransferAction> {
        if (record.phase != TransferPhase.receiving && record.phase != TransferPhase.sending) {
            return emptyList()
        }
        if (now - lastActivity < STALL_MILLIS) return emptyList()

        record = record.copy(phase = TransferPhase.failed)
        return listOf(TransferAction.Abort)
    }

    fun handle(event: TransferEvent): List<TransferAction> = when {
        record.phase == TransferPhase.idle && event is TransferEvent.Offer -> {
            record = record.copy(phase = TransferPhase.offered)
            emptyList()
        }

        record.phase == TransferPhase.offered && event is TransferEvent.Accept -> {
            when {
                event.offset < record.bytesConfirmed -> {
                    // Seeking backwards would duplicate data that is already on disk.
                    record = record.copy(phase = TransferPhase.failed)
                    listOf(TransferAction.Abort)
                }
                event.offset > record.size -> {
                    record = record.copy(phase = TransferPhase.failed)
                    listOf(TransferAction.Abort)
                }
                else -> {
                    record = record.copy(
                        phase = TransferPhase.receiving,
                        bytesConfirmed = event.offset,
                    )
                    if (event.offset == 0uL) emptyList() else listOf(TransferAction.SeekTo(event.offset))
                }
            }
        }

        record.phase == TransferPhase.offered && event is TransferEvent.Reject -> {
            record = record.copy(phase = TransferPhase.failed)
            emptyList()
        }

        record.phase == TransferPhase.offered && event is TransferEvent.Cancel -> {
            record = record.copy(phase = TransferPhase.cancelled)
            emptyList()
        }

        record.phase == TransferPhase.receiving && event is TransferEvent.Chunk -> {
            if (event.offset != record.bytesConfirmed) {
                record = record.copy(phase = TransferPhase.failed)
                listOf(TransferAction.Abort)
            } else {
                record = record.copy(
                    bytesConfirmed = record.bytesConfirmed + event.length.toULong()
                )
                lastActivity = System.currentTimeMillis()
                emptyList()
            }
        }

        record.phase == TransferPhase.receiving && event is TransferEvent.Complete -> {
            record = record.copy(phase = TransferPhase.verifying)
            listOf(TransferAction.ComputeHash)
        }

        record.phase == TransferPhase.verifying && event is TransferEvent.HashOk -> {
            record = record.copy(phase = TransferPhase.completed)
            listOf(TransferAction.CommitFile)
        }

        record.phase == TransferPhase.verifying && event is TransferEvent.HashMismatch -> {
            record = record.copy(phase = TransferPhase.failed)
            listOf(TransferAction.DeletePartial)
        }

        record.phase == TransferPhase.receiving && event is TransferEvent.Stall -> {
            record = record.copy(phase = TransferPhase.failed)
            listOf(TransferAction.Abort)
        }

        record.phase == TransferPhase.receiving && event is TransferEvent.Cancel -> {
            record = record.copy(phase = TransferPhase.cancelled)
            listOf(TransferAction.DeletePartial)
        }

        // A frame in the wrong phase is ignored rather than failing: a peer can race a cancel
        // against a chunk that was already in flight.
        else -> emptyList()
    }

    companion object {
        const val STALL_MILLIS = 30_000L
    }
}
