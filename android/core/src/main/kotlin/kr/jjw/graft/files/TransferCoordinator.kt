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

/** How much unsent data may sit in the WebSocket queue before the sender waits. */
private const val MAX_BACKLOG_BYTES = 4L * 1024 * 1024

/** How often a partial receive is written to the resume store. */
private const val RESUME_SAVE_INTERVAL = 1024L * 1024

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
    private val sendBinary: (ByteArray) -> Boolean,
    private val backlog: () -> Long = { 0 },
    private val records: TransferRecordStore? = null,
    private val receiversFactory: FileReceivingFactory = DirectoryFileReceiving.factory(directory),
) {
    private val receivers = mutableMapOf<UInt, FileReceiving>()
    private val senders = mutableMapOf<UInt, FileSender>()
    private val offers = mutableMapOf<UInt, FileOffer>()
    private val persistedBytes = mutableMapOf<UInt, Long>()

    init {
        records?.purgeExpired()
    }

    val progress: MutableMap<UInt, TransferProgress> = mutableMapOf()
    val failures: MutableMap<UInt, TransferFailureKind> = mutableMapOf()
    val completed: MutableSet<UInt> = mutableSetOf()

    /** Where each committed receive landed, for the row's Open action. */
    val locations: MutableMap<UInt, String> = mutableMapOf()

    /** Cancels everything in flight; called when a session ends so partial rows do not linger. */
    @Synchronized
    fun cancelAll() {
        receivers.values.forEach { runCatching { it.cancel() } }
        receivers.clear()
        senders.keys.forEach { senders.remove(it) }
        offers.clear()
        progress.clear()
    }

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
        try {
        when (envelope.type) {
            "file.offer" -> handleOffer(envelope)
            "file.accept" -> handleAccept(envelope)
            "file.reject" -> handleReject(envelope)
            "file.complete" -> handleComplete(envelope)
            "file.cancel" -> handleCancel(envelope)
            else -> Unit
        }
        } catch (error: Exception) {
            // A full disk or a revoked provider must not escape into the socket loop.
            tidOf(envelope)?.let { tid ->
                failures[tid] = TransferFailureKind.READ_FAILED
                runCatching { receivers.remove(tid)?.cancel() }
                runCatching { sendControl(errorEnvelope("bad_payload", "could not save the file")) }
            }
        }
    }

    @Synchronized
    fun onBinary(raw: ByteArray) {
        val frame = runCatching { BinaryFrame.decode(raw) }.getOrNull() ?: return
        val receiver = receivers[frame.transferId] ?: return

        try {
            receiver.write(frame)
            recordProgress(frame.transferId, receiver)
            persistResume(frame.transferId, receiver)
            progress[frame.transferId] = TransferProgress(
                name = progress[frame.transferId]?.name ?: "file",
                confirmed = receiver.confirmedBytes,
                total = offers[frame.transferId]?.size?.toLong() ?: receiver.confirmedBytes,
                direction = TransferDirection.receive,
            )
        } catch (error: java.io.IOException) {
            failures[frame.transferId] = TransferFailureKind.READ_FAILED
            runCatching { receiver.cancel() }
            receivers.remove(frame.transferId)
            sendControl(errorEnvelope("bad_payload", "could not save the file"))
        } catch (error: FileReceiverException) {
            failures[frame.transferId] = TransferFailureKind.WRONG_OFFSET
            runCatching { receiver.cancel() }
            receivers.remove(frame.transferId)
            sendControl(errorEnvelope("bad_payload", "chunk at the wrong offset"))
        }
    }

    /**
     * Remembers where an interrupted receive stopped.
     *
     * Writing the record per chunk would hit the disk 16 times a megabyte, so it is written once a
     * megabyte and again when the transfer ends.
     */
    private fun persistResume(tid: UInt, receiver: FileReceiving) {
        val store = records ?: return
        val offer = offers[tid] ?: return
        val confirmed = receiver.confirmedBytes
        val last = persistedBytes[tid] ?: 0L
        if (confirmed - last < RESUME_SAVE_INTERVAL) return
        persistedBytes[tid] = confirmed
        store.upsert(
            ResumeRecord(
                name = offer.name,
                directory = directory.path,
                size = offer.size.toLong(),
                bytesConfirmed = confirmed,
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    private fun handleOffer(envelope: Envelope) {
        // A retried transfer id starts clean: an earlier failure must not describe this attempt.
        tidOf(envelope)?.let { tid ->
            failures.remove(tid)
            locations.remove(tid)
            completed.remove(tid)
        }
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

        // One check for every destination: MediaStore used to accept names the fallback refused.
        val safeName = SafeFilename.sanitize(name)
        if (safeName == null) {
            failures[tid] = TransferFailureKind.UNSAFE_NAME
            sendControl(errorEnvelope("bad_payload", "unsafe file name"))
            return
        }

        val offer = FileOffer(
            transferId = tid,
            name = safeName,
            size = size.toULong(),
            mime = "application/octet-stream",
        )
        offers[tid] = offer

        try {
            val receiver = receiversFactory.create(safeName)
            // Resume from whatever a previous attempt left on disk, but only when a stored record
            // agrees; otherwise a leftover file from an unrelated transfer could be appended to.
            val onDisk = ResumeLookup.offset(directory, safeName, size)
            val stored = records?.find(safeName, directory.path, size, onDisk)
            val resumeFrom = stored?.bytesConfirmed ?: 0
            val offset = receiver.begin(offer, resumeFrom)
            receivers[tid] = receiver
            progress[tid] = TransferProgress(
                name = safeName,
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
                awaitDrain()
                if (!sendBinary(frame.encoded())) {
                    // OkHttp closes the socket when its queue overflows; stopping here keeps the
                    // failure visible instead of reporting progress into a dead connection.
                    failures[tid] = TransferFailureKind.READ_FAILED
                    senders.remove(tid)
                    return
                }
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
            // Nothing acks a completed send, so record it here or it stays "in progress" forever.
            completed.add(tid)
            senders.remove(tid)
            progress[tid] = TransferProgress(
                name = sender.name,
                confirmed = sender.size,
                total = sender.size,
                direction = TransferDirection.send,
            )
        } catch (error: Exception) {
            failures[tid] = TransferFailureKind.READ_FAILED
            senders.remove(tid)
            sendControl(errorEnvelope("bad_payload", "could not read the file"))
        }
    }

    /**
     * Waits while the socket queue is full.
     *
     * Reading a local file is faster than Wi-Fi, so without this the send outruns the socket and
     * OkHttp drops the connection. Gives up after ten seconds so a stuck socket fails the transfer
     * instead of blocking the reader forever.
     */
    private fun awaitDrain() {
        val deadline = System.currentTimeMillis() + 10_000
        while (backlog() > MAX_BACKLOG_BYTES) {
            if (System.currentTimeMillis() > deadline) {
                throw java.io.IOException("socket stalled")
            }
            Thread.sleep(10)
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
            completed.add(tid)
            failures.remove(tid)
            receiver.destination?.takeIf { it.isNotBlank() }?.let { locations[tid] = it }
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
            runCatching { receiver.cancel() }
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
