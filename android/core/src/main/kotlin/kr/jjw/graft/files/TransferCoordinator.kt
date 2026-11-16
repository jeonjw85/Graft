package kr.jjw.graft.files

import java.io.File
import kr.jjw.graft.core.BinaryFrame
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.FileOffer
import kr.jjw.graft.core.TransferDirection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TransferProgress(
    val name: String,
    val confirmed: Long,
    val total: Long,
    val direction: TransferDirection,
) {
    val fraction: Double get() = if (total == 0L) 0.0 else confirmed.toDouble() / total
}

enum class TransferFailureKind {
    UNPAIRED_PEER,
    DUPLICATE_TRANSFER,
    UNSAFE_NAME,
    WRONG_OFFSET,
    HASH_MISMATCH,
    READ_FAILED,

    /** The peer refused the offer. */
    REJECTED,
}

/**
 * Runs file transfers for one connection: answers offers, writes incoming data, and streams
 * outgoing files. Auto-accept applies only when [isPaired] returns true.
 */
class TransferCoordinator(
    private val directory: File,
    private val isPaired: () -> Boolean,
    private val sendControl: (Envelope) -> Unit,
    private val sendBinary: (ByteArray) -> Unit,
    private val records: TransferRecordStore? = null,
    private val receiversFactory: FileReceivingFactory = DirectoryFileReceiving.factory(directory),
) {
    private val receivers = mutableMapOf<UInt, FileReceiving>()
    private val senders = mutableMapOf<UInt, FileSender>()
    private val offers = mutableMapOf<UInt, FileOffer>()

    init {
        records?.purgeExpired()
    }

    val progress: MutableMap<UInt, TransferProgress> = mutableMapOf()
    val failures: MutableMap<UInt, TransferFailureKind> = mutableMapOf()
    val completed: MutableSet<UInt> = mutableSetOf()

    @Synchronized
    fun offer(file: File, transferId: UInt): FileOffer {
        require(senders[transferId] == null) { "transfer ${transferId} is already active" }

        val sender = FileSender(file)
        senders[transferId] = sender
        val offer = sender.offer(transferId.toInt())
        offers[transferId] = offer
        progress[transferId] = TransferProgress(
            name = offer.name,
            confirmed = 0,
            total = offer.size.toLong(),
            direction = TransferDirection.send,
        )

        sendControl(
            envelope("file.offer", buildJsonObject {
                put("tid", JsonPrimitive(transferId.toLong()))
                put("name", JsonPrimitive(offer.name))
                put("size", JsonPrimitive(offer.size.toLong()))
                put("mime", JsonPrimitive(offer.mime))
            })
        )
        return offer
    }

    @Synchronized
    fun onControl(envelope: Envelope) {
        when (envelope.type) {
            "file.offer" -> handleOffer(envelope)
            "file.accept" -> handleAccept(envelope)
            "file.reject" -> handleReject(envelope)
            "file.complete" -> handleComplete(envelope)
            "file.cancel" -> handleCancel(envelope)
            else -> Unit
        }
    }

    @Synchronized
    fun onBinary(raw: ByteArray) {
        val frame = runCatching { BinaryFrame.decode(raw) }.getOrNull() ?: return
        val receiver = receivers[frame.transferId] ?: return

        try {
            receiver.write(frame)
            recordProgress(frame.transferId, receiver)
            progress[frame.transferId] = TransferProgress(
                name = progress[frame.transferId]?.name ?: "file",
                confirmed = receiver.confirmedBytes,
                total = offers[frame.transferId]?.size?.toLong() ?: receiver.confirmedBytes,
                direction = TransferDirection.receive,
            )
        } catch (error: FileReceiverException) {
            failures[frame.transferId] = TransferFailureKind.WRONG_OFFSET
            receivers.remove(frame.transferId)
            sendControl(errorEnvelope("bad_payload", "chunk at the wrong offset"))
        }
    }

    private fun handleOffer(envelope: Envelope) {
        if (!isPaired()) {
            sendControl(errorEnvelope("unpaired", "pairing required"))
            return
        }

        val payload = runCatching { envelope.payload.jsonObject }.getOrNull()
        val tid = payload?.get("tid")?.jsonPrimitive?.longOrNull?.toUInt()
        val name = payload?.get("name")?.jsonPrimitive?.content
        val size = payload?.get("size")?.jsonPrimitive?.longOrNull

        if (tid == null || name == null || size == null) {
            sendControl(errorEnvelope("bad_payload", "file.offer fields missing"))
            return
        }
        if (receivers.containsKey(tid)) {
            sendControl(errorEnvelope("transfer_not_found", "transfer id already active"))
            return
        }

        val offer = FileOffer(
            transferId = tid,
            name = name,
            size = size.toULong(),
            mime = "application/octet-stream",
        )
        offers[tid] = offer

        try {
            val receiver = receiversFactory.create(name)
            // Resume from whatever a previous attempt left on disk, but only when a stored record
            // agrees; otherwise a leftover file from an unrelated transfer could be appended to.
            val onDisk = ResumeLookup.offset(directory, name, size)
            val stored = records?.find(name, directory.path, size, onDisk)
            val resumeFrom = stored?.bytesConfirmed ?: 0
            val offset = receiver.begin(offer, resumeFrom)
            receivers[tid] = receiver
            progress[tid] = TransferProgress(
                name = name,
                confirmed = offset,
                total = size,
                direction = TransferDirection.receive,
            )
            sendControl(
                envelope("file.accept", buildJsonObject {
                    put("tid", JsonPrimitive(tid.toLong()))
                    put("offset", JsonPrimitive(offset))
                })
            )
        } catch (error: FileReceiverException) {
            failures[tid] = TransferFailureKind.UNSAFE_NAME
            sendControl(errorEnvelope("bad_payload", "unsafe file name"))
        }
    }

    private fun handleAccept(envelope: Envelope) {
        val payload = runCatching { envelope.payload.jsonObject }.getOrNull()
        val tid = payload?.get("tid")?.jsonPrimitive?.longOrNull?.toUInt() ?: return
        val offset = payload?.get("offset")?.jsonPrimitive?.longOrNull ?: return
        val sender = senders[tid] ?: return

        try {
            for (frame in sender.stream(tid.toInt(), offset)) {
                sendBinary(frame.encoded())
                progress[tid] = TransferProgress(
                    name = sender.name,
                    confirmed = frame.offset.toLong() + frame.data.size,
                    total = sender.size,
                    direction = TransferDirection.send,
                )
            }
            sendControl(
                envelope("file.complete", buildJsonObject {
                    put("tid", JsonPrimitive(tid.toLong()))
                    put("sha256", JsonPrimitive(sender.sha256()))
                })
            )
        } catch (error: Exception) {
            failures[tid] = TransferFailureKind.READ_FAILED
            sendControl(errorEnvelope("bad_payload", "could not read the file"))
        }
    }

    private fun handleReject(envelope: Envelope) {
        val tid = tidOf(envelope) ?: return
        failures[tid] = TransferFailureKind.REJECTED
        senders.remove(tid)
    }

    private fun handleComplete(envelope: Envelope) {
        val payload = runCatching { envelope.payload.jsonObject }.getOrNull()
        val tid = payload?.get("tid")?.jsonPrimitive?.longOrNull?.toUInt() ?: return
        val expected = payload?.get("sha256")?.jsonPrimitive?.content ?: return
        val receiver = receivers[tid] ?: return

        try {
            val ok = receiver.finish(expected)
            if (!ok) throw FileReceiverException(FileReceiverErrorKind.HASH_MISMATCH)
            val file = receiver as? DirectoryFileReceiving
            completed.add(tid)
            offers[tid]?.let { records?.remove(it.name, directory.path) }
            progress[tid] = TransferProgress(
                name = offers[tid]?.name ?: "file",
                confirmed = offers[tid]?.size?.toLong() ?: 0,
                total = offers[tid]?.size?.toLong() ?: 0,
                direction = TransferDirection.receive,
            )
            receivers.remove(tid)
        } catch (error: FileReceiverException) {
            failures[tid] = if (error.kind == FileReceiverErrorKind.HASH_MISMATCH) {
                TransferFailureKind.HASH_MISMATCH
            } else {
                TransferFailureKind.WRONG_OFFSET
            }
            receivers.remove(tid)
            // A corrupt partial must not be resumed onto, so the record goes with it.
            offers[tid]?.let { records?.remove(it.name, directory.path) }
            sendControl(errorEnvelope("hash_mismatch", "hash mismatch"))
        }
    }

    private fun handleCancel(envelope: Envelope) {
        val tid = tidOf(envelope) ?: return
        receivers[tid]?.cancel()
        receivers.remove(tid)
        senders.remove(tid)
    }

    private fun recordProgress(tid: UInt, receiver: FileReceiving) {
        val store = records ?: return
        val offer = offers[tid] ?: return
        store.upsert(
            ResumeRecord(
                name = offer.name,
                directory = directory.path,
                size = offer.size.toLong(),
                bytesConfirmed = receiver.confirmedBytes,
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun tidOf(envelope: Envelope): UInt? = runCatching {
        envelope.payload.jsonObject["tid"]?.jsonPrimitive?.longOrNull?.toUInt()
    }.getOrNull()

    private fun envelope(type: String, payload: kotlinx.serialization.json.JsonObject) =
        Envelope.create(type, payload)

    private fun errorEnvelope(code: String, message: String) = Envelope.create(
        "error",
        buildJsonObject {
            put("code", JsonPrimitive(code))
            put("message", JsonPrimitive(message))
        }
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(
    key: String,
    value: JsonPrimitive,
) {
    put(key, value as kotlinx.serialization.json.JsonElement)
}
