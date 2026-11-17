package kr.jjw.graft.e2e

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.PairingOffer
import kr.jjw.graft.core.PairingUri
import kr.jjw.graft.core.identity.DeviceIdentity
import kr.jjw.graft.core.identity.InMemoryKeyStoreBackend
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.GraftClient
import kr.jjw.graft.core.transport.IdentityKeyManager
import kr.jjw.graft.core.transport.PinnedTrustManager
import kr.jjw.graft.files.DirectoryFileReceiving
import kr.jjw.graft.files.TransferCoordinator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Connects, pairs, and drives one file transfer, keeping the socket open until it completes.
 *
 * Prints `TRANSFER_OK <path> <sha256>` on success. A caller can rely on that line; a crash or a
 * dropped connection prints nothing and exits non-zero.
 */
fun runTransfer(uri: String, receiveDir: String, sendPath: String?): Int {
    val offer = PairingUri.parse(uri)
    val directory = File(receiveDir).apply { mkdirs() }

    val identity = DeviceIdentity.loadOrCreate(InMemoryKeyStoreBackend(), "transfer")
    val client = GraftClient(
        baseUrl = "wss://${offer.host}:${offer.port}/graft",
        trustManager = PinnedTrustManager { setOf(offer.fingerprint) },
        keyManager = IdentityKeyManager(
            alias = "transfer",
            privateKey = identity.privateKey,
            chain = arrayOf(identity.certificate),
        ),
    )

    val coordinator = TransferCoordinator(
        directory = directory,
        isPaired = { true },
        sendControl = { envelope -> client.send(envelope) },
        sendBinary = { bytes -> client.sendBinary(bytes) },
        receiversFactory = DirectoryFileReceiving.factory(directory),
    )

    val done = CountDownLatch(1)
    val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val completedTid = java.util.concurrent.atomic.AtomicReference<UInt?>(null)
    var sentOffer = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val job = scope.launch {
        runCatching {
            withTimeout(120_000) {
                client.connect().collect { event ->
                    when (event) {
                        is ConnectionEvent.Secure -> {
                            client.send(pairRequest(offer.token))
                        }

                        is ConnectionEvent.Message -> {
                            coordinator.onControl(event.envelope)
                            when (event.envelope.type) {
                                "pair.ok" -> client.send(hello())
                                "file.offer", "file.accept" ->
                                    if (sendPath != null && !sentOffer) {
                                        coordinator.offer(File(sendPath), 1u)
                                        sentOffer = true
                                    }
                                "error" -> {
                                    failure.set("server error: ${event.envelope.payload}")
                                    done.countDown()
                                }
                                else -> Unit
                            }

                            val finished = coordinator.completed.firstOrNull()
                            if (finished != null) {
                                completedTid.set(finished)
                                done.countDown()
                            }
                        }

                        is ConnectionEvent.Binary -> {
                            coordinator.onBinary(event.data)
                            val finished = coordinator.completed.firstOrNull()
                            if (finished != null) {
                                completedTid.set(finished)
                                done.countDown()
                            }
                        }

                        is ConnectionEvent.Closed -> {
                            if (coordinator.completed.isEmpty()) {
                                failure.set("connection closed before the transfer completed")
                            }
                            done.countDown()
                        }

                        ConnectionEvent.Connecting -> Unit
                    }
                }
            }
        }.onFailure { failure.set(it.message ?: it.toString()) }
    }

    val finished = done.await(130, TimeUnit.SECONDS)
    job.cancel()
    client.close()

    if (!finished || failure.get() != null) {
        System.err.println("FAILED: ${failure.get() ?: "timed out"}")
        return 1
    }

    val tid = completedTid.get()
    if (sendPath != null) {
        val digest = sha256(File(sendPath))
        println("TRANSFER_OK $sendPath $digest")
    } else {
        val progress = tid?.let { coordinator.progress[it] }
        val name = progress?.name
        if (name == null) {
            System.err.println("FAILED: no received file name recorded")
            return 1
        }
        val file = File(directory, name)
        if (!file.exists()) {
            System.err.println("FAILED: expected file missing: ${file.path}")
            return 1
        }
        println("TRANSFER_OK ${file.path} ${sha256(file)}")
    }
    exitProcess(0)
}

private fun pairRequest(token: String) = Envelope.create(
    type = "pair.request",
    payload = buildJsonObject {
        put("token", token)
        put("deviceId", "transfer-peer")
        put("name", "Transfer Peer")
        put("platform", "android")
    }
)

private fun hello() = Envelope.create(
    type = "hello",
    payload = buildJsonObject { put("proto", 1) }
)

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
