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

/** How often background send progress notifies the UI. */
private const val SEND_PROGRESS_INTERVAL = 512L * 1024

/** Protocol §3.1: a receive with no chunk for 30 seconds fails and removes its partial file. */
const val STALL_TIMEOUT_MILLIS = 30_000L

enum class TransferFailureKind {
    UNPAIRED_PEER,
    DUPLICATE_TRANSFER,
    UNSAFE_NAME,
    WRONG_OFFSET,
    HASH_MISMATCH,
    READ_FAILED,

    /** The peer refused the offer. */
    REJECTED,

    /** No chunk arrived within 30 seconds. */
    STALLED,
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
    private val sendRunner: ((() -> Unit) -> Unit)? = null,
    private val onProgressChanged: (() -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val receivers = mutableMapOf<UInt, FileReceiving>()
    private val senders = mutableMapOf<UInt, FileSender>()
    private val offers = mutableMapOf<UInt, FileOffer>()
    private val persistedBytes = mutableMapOf<UInt, Long>()
    private val lastReceiveActivity = mutableMapOf<UInt, Long>()
    private val activeSends = mutableSetOf<UInt>()
    private val cancelledSends = java.util.concurrent.ConcurrentHashMap.newKeySet<UInt>()
    @Volatile private var cancelledAll = false

    init {
        records?.purgeExpired()
    }

    val progress: MutableMap<UInt, TransferProgress> = mutableMapOf()
    val failures: MutableMap<UInt, TransferFailureKind> = mutableMapOf()
    val completed: MutableSet<UInt> = mutableSetOf()

    /** Where each committed receive landed, for the row's Open action. */
    val locations: MutableMap<UInt, String> = java.util.concurrent.ConcurrentHashMap()

    /** Thread-safe snapshot of in-flight and completed progress for UI observers. */
    @Synchronized
    fun progressSnapshot(): Map<UInt, TransferProgress> = progress.toMap()

    /** Thread-safe snapshot of finished transfer IDs and their failure reasons. */
    @Synchronized
    fun finishedSnapshot(): Pair<Set<UInt>, Map<UInt, TransferFailureKind>> =
        completed.toSet() to failures.toMap()

    /** Fails any active receive with no chunk for [STALL_TIMEOUT_MILLIS] and deletes its partial file. */
    @Synchronized
    fun checkStalls(nowMillis: Long = clock()): Boolean {
        val stalled = lastReceiveActivity.entries
            .filter { (tid, last) -> receivers.containsKey(tid) && nowMillis - last >= STALL_TIMEOUT_MILLIS }
            .map { it.key }
        if (stalled.isEmpty()) return false

        for (tid in stalled) {
            runCatching { receivers.remove(tid)?.discard() }
            persistedBytes.remove(tid)
            lastReceiveActivity.remove(tid)
            offers[tid]?.let { records?.remove(it.name, directory.path) }
            failures[tid] = TransferFailureKind.STALLED
            runCatching {
                sendControl(envelope("file.cancel", buildJsonObject { put("tid", JsonPrimitive(tid.toLong())) }))
            }
        }
        return true
    }

    /** Cancels a single in-flight transfer (send or receive), notifies the peer, and cleans up. */
    @Synchronized
    fun cancel(transferId: UInt) {
        var didCancel = false
        receivers.remove(transferId)?.let { receiver ->
            runCatching { receiver.discard() }
            persistedBytes.remove(transferId)
            lastReceiveActivity.remove(transferId)
            offers[transferId]?.let { records?.remove(it.name, directory.path) }
            didCancel = true
        }
        if (senders.remove(transferId) != null || activeSends.remove(transferId)) {
            cancelledSends.add(transferId)
            didCancel = true
        }
        progress.remove(transferId)
        offers.remove(transferId)

        if (didCancel) {
            runCatching {
                sendControl(envelope("file.cancel", buildJsonObject { put("tid", JsonPrimitive(transferId.toLong())) }))
            }
            onProgressChanged?.invoke()
        }
    }

    /** Cancels everything in flight; called when a session ends so partial rows do not linger. */
    @Synchronized
    fun cancelAll() {
        cancelledAll = true
        receivers.forEach { (tid, receiver) ->
            persistResume(tid, receiver, force = true)
            runCatching { receiver.cancel() }
        }
        receivers.clear()
        persistedBytes.clear()
        lastReceiveActivity.clear()
        senders.clear()
        activeSends.clear()
        cancelledSends.clear()
        offers.clear()
        progress.clear()
    }

    @Synchronized
    fun offer(file: File, transferId: UInt, mime: String = "application/octet-stream"): FileOffer {
        require(senders[transferId] == null) { "transfer ${transferId} is already active" }

        val sender = FileSender(file)
        senders[transferId] = sender
        val offer = sender.offer(transferId.toInt(), mime = mime)
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
        val frame = runCatching { BinaryFrame.decode(raw) }.getOrNull()
        if (frame == null) {
            sendControl(errorEnvelope("bad_payload", "invalid binary frame"))
            return
        }
        val receiver = receivers[frame.transferId] ?: return
        lastReceiveActivity[frame.transferId] = clock()

        try {
            receiver.write(frame)
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
            persistedBytes.remove(frame.transferId)
            lastReceiveActivity.remove(frame.transferId)
            sendControl(errorEnvelope("bad_payload", "could not save the file"))
        } catch (error: FileReceiverException) {
            failures[frame.transferId] = TransferFailureKind.WRONG_OFFSET
            runCatching { receiver.cancel() }
            receivers.remove(frame.transferId)
            persistedBytes.remove(frame.transferId)
            lastReceiveActivity.remove(frame.transferId)
            sendControl(errorEnvelope("bad_payload", "chunk at the wrong offset"))
        }
    }

    /**
     * Remembers where an interrupted receive stopped.
     *
     * Writes on the first chunk, then once per megabyte, and again when forced on cancel/stop so
     * disk I/O stays low while partial transfers remain resumable at their exact byte offset.
     */
    private fun persistResume(tid: UInt, receiver: FileReceiving, force: Boolean = false) {
        val store = records ?: return
        val offer = offers[tid] ?: return
        val confirmed = receiver.confirmedBytes
        if (confirmed <= 0L) return
        val last = persistedBytes[tid]
        if (!force && last != null && confirmed - last < RESUME_SAVE_INTERVAL) return
        if (last == confirmed) return
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

        val mime = payload["mime"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        val offer = FileOffer(
            transferId = tid,
            name = safeName,
            size = size.toULong(),
            mime = mime,
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
            lastReceiveActivity[tid] = clock()
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
        if (!activeSends.add(tid)) return
        cancelledAll = false
        cancelledSends.remove(tid)

        val runner = sendRunner
        if (runner != null) {
            runner { streamSend(tid, offset, sender) }
        } else {
            streamSend(tid, offset, sender)
        }
    }

    private fun streamSend(tid: UInt, offset: Long, sender: FileSender) {
        try {
            var published = offset
            for (frame in sender.stream(tid.toInt(), offset)) {
                if (isSendCancelled(tid)) {
                    cleanupCancelledSend(tid)
                    return
                }
                awaitDrain(tid)
                if (isSendCancelled(tid)) {
                    cleanupCancelledSend(tid)
                    return
                }
                if (!sendBinary(frame.encoded())) {
                    // OkHttp closes the socket when its queue overflows; stopping here keeps the
                    // failure visible instead of reporting progress into a dead connection.
                    synchronized(this) {
                        failures[tid] = TransferFailureKind.READ_FAILED
                        senders.remove(tid)
                        activeSends.remove(tid)
                    }
                    onProgressChanged?.invoke()
                    return
                }
                val confirmed = frame.offset.toLong() + frame.data.size
                synchronized(this) {
                    progress[tid] = TransferProgress(
                        name = sender.name,
                        confirmed = confirmed,
                        total = sender.size,
                        direction = TransferDirection.send,
                    )
                }
                if (confirmed - published >= SEND_PROGRESS_INTERVAL || confirmed == sender.size) {
                    published = confirmed
                    onProgressChanged?.invoke()
                }
            }
            if (isSendCancelled(tid)) {
                cleanupCancelledSend(tid)
                return
            }
            val digest = sender.sha256()
            sendControl(
                envelope("file.complete", buildJsonObject {
                    put("tid", JsonPrimitive(tid.toLong()))
                    put("sha256", JsonPrimitive(digest))
                })
            )
            // Nothing acks a completed send, so record it here or it stays "in progress" forever.
            synchronized(this) {
                completed.add(tid)
                senders.remove(tid)
                activeSends.remove(tid)
                progress[tid] = TransferProgress(
                    name = sender.name,
                    confirmed = sender.size,
                    total = sender.size,
                    direction = TransferDirection.send,
                )
            }
            onProgressChanged?.invoke()
        } catch (error: Exception) {
            if (isSendCancelled(tid)) {
                cleanupCancelledSend(tid)
                return
            }
            synchronized(this) {
                failures[tid] = TransferFailureKind.READ_FAILED
                senders.remove(tid)
                activeSends.remove(tid)
            }
            runCatching { sendControl(errorEnvelope("bad_payload", "could not read the file")) }
            onProgressChanged?.invoke()
        }
    }

    private fun isSendCancelled(tid: UInt): Boolean = cancelledAll || cancelledSends.contains(tid)

    private fun cleanupCancelledSend(tid: UInt) {
        synchronized(this) {
            senders.remove(tid)
            activeSends.remove(tid)
            cancelledSends.remove(tid)
        }
        onProgressChanged?.invoke()
    }

    /**
     * Waits while the socket queue is full.
     *
     * Reading a local file is faster than Wi-Fi, so without this the send outruns the socket and
     * OkHttp drops the connection. Gives up after ten seconds so a stuck socket fails the transfer
     * instead of blocking the reader forever.
     */
    private fun awaitDrain(tid: UInt) {
        val deadline = System.currentTimeMillis() + 10_000
        while (backlog() > MAX_BACKLOG_BYTES) {
            if (isSendCancelled(tid)) return
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
        activeSends.remove(tid)
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
            persistedBytes.remove(tid)
            lastReceiveActivity.remove(tid)
        } catch (error: FileReceiverException) {
            failures[tid] = if (error.kind == FileReceiverErrorKind.HASH_MISMATCH) {
                TransferFailureKind.HASH_MISMATCH
            } else {
                TransferFailureKind.WRONG_OFFSET
            }
            runCatching { receiver.cancel() }
            receivers.remove(tid)
            persistedBytes.remove(tid)
            lastReceiveActivity.remove(tid)
            // A corrupt partial must not be resumed onto, so the record goes with it.
            offers[tid]?.let { records?.remove(it.name, directory.path) }
            sendControl(errorEnvelope("hash_mismatch", "hash mismatch"))
        }
    }

    private fun handleCancel(envelope: Envelope) {
        val tid = tidOf(envelope) ?: return
        receivers[tid]?.let { receiver ->
            persistResume(tid, receiver, force = true)
            receiver.cancel()
        }
        receivers.remove(tid)
        persistedBytes.remove(tid)
        lastReceiveActivity.remove(tid)
        senders.remove(tid)
        progress.remove(tid)
        cancelledSends.add(tid)
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
