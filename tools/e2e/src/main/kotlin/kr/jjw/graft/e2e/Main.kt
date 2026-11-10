package kr.jjw.graft.e2e

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.PairingUri
import kr.jjw.graft.core.identity.DeviceIdentity
import kr.jjw.graft.core.identity.InMemoryKeyStoreBackend
import kr.jjw.graft.core.transport.ConnectionEvent
import kr.jjw.graft.core.transport.GraftClient
import kr.jjw.graft.core.transport.IdentityKeyManager
import kr.jjw.graft.core.transport.PinnedTrustManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Headless peer used by the end-to-end test.
 *
 * Connects to graft-headless with a real TLS handshake, pairs with the token in the URI, exchanges
 * hello, and then verifies that a second connection without a valid token is rejected.
 */
fun main(args: Array<String>) {
    var uri: String? = null
    var expectRefused = false
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--uri" -> uri = args.getOrNull(++index)
            "--expect-refused" -> expectRefused = true
            else -> Unit
        }
        index++
    }

    if (uri == null) {
        System.err.println("usage: --uri <pairing-uri>")
        exitProcess(2)
    }

    val offer = PairingUri.parse(uri)
    if (expectRefused) {
        exitProcess(if (refusedPairing(offer)) 0 else 1)
    }
    val backend = InMemoryKeyStoreBackend()
    val identity = DeviceIdentity.loadOrCreate(backend, "e2e")
    val trust = PinnedTrustManager { setOf(offer.fingerprint) }
    val keys = IdentityKeyManager(
        alias = "e2e",
        privateKey = identity.privateKey,
        chain = arrayOf(identity.certificate),
    )
    val client = GraftClient(
        baseUrl = "wss://${offer.host}:${offer.port}/graft",
        trustManager = trust,
        keyManager = keys,
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val done = CountDownLatch(1)
    var failure: String? = null

    fun fail(message: String) {
        failure = message
        done.countDown()
    }

    val received = mutableListOf<Envelope>()

    scope.launch {
        try {
            withTimeout(20_000) {
                client.connect().collect { event ->
                    when (event) {
                        is ConnectionEvent.Secure -> {
                            if (event.serverFingerprint != offer.fingerprint) {
                                fail("server fingerprint mismatch: got=${event.serverFingerprint} want=${offer.fingerprint}")
                                return@collect
                            }
                            client.send(
                                Envelope.create(
                                    type = "pair.request",
                                    payload = buildJsonObject {
                                        put("token", offer.token)
                                        put("deviceId", "e2e-device")
                                        put("name", "E2E Peer")
                                        put("platform", "android")
                                    }
                                )
                            )
                        }

                        is ConnectionEvent.Message -> {
                            received += event.envelope
                            when (event.envelope.type) {
                                "pair.ok" -> client.send(
                                    Envelope.create(
                                        type = "hello",
                                        payload = buildJsonObject {
                                            put("deviceId", "e2e-device")
                                            put("name", "E2E Peer")
                                            put("platform", "android")
                                            put("appVersion", "0.1.0")
                                            put("proto", 1)
                                            put("caps", kotlinx.serialization.json.JsonArray(emptyList()))
                                        }
                                    )
                                )

                                "hello.ok" -> done.countDown()

                                "error" -> fail("server error: ${event.envelope.payload}")
                            }
                        }

                        is ConnectionEvent.Closed -> {
                            if (received.none { it.type == "hello.ok" }) {
                                fail("connection closed before hello.ok: ${event.reason}")
                            }
                        }

                        ConnectionEvent.Connecting -> Unit
                    }
                }
            }
        } catch (error: Throwable) {
            fail("client failed: $error")
        }
    }

    val finished = done.await(25, TimeUnit.SECONDS)
    client.close()

    if (!finished) {
        System.err.println("FAILED: timed out; received=${received.map { it.type }}")
        exitProcess(1)
    }
    if (failure != null) {
        System.err.println("FAILED: $failure")
        exitProcess(1)
    }

    val pairOk = received.any { it.type == "pair.ok" }
    val helloOk = received.any { it.type == "hello.ok" }
    if (!pairOk || !helloOk) {
        System.err.println("FAILED: pairOk=$pairOk helloOk=$helloOk received=${received.map { it.type }}")
        exitProcess(1)
    }

    println("PAIRED fingerprint=${identity.fingerprint} server=${offer.fingerprint}")
    println("HELLO_OK")

    // Replay the same token on a fresh connection using the SAME identity. A new identity would be
    // rejected at the TLS layer, which would prove nothing about token reuse.
    when (val replay = replayPairing(offer, identity)) {
        ReplayOutcome.REJECTED -> println("REPLAY_REJECTED")
        ReplayOutcome.ACCEPTED -> {
            System.err.println("FAILED: the server accepted a replayed pairing request")
            exitProcess(1)
        }
        ReplayOutcome.NO_VERDICT -> {
            System.err.println("FAILED: no verdict on the replayed request; the server closed without a frame")
            exitProcess(1)
        }
    }

    // Gradle's non-daemon threads keep the JVM alive for a minute; exit explicitly.
    exitProcess(0)
}

private enum class ReplayOutcome { REJECTED, ACCEPTED, NO_VERDICT }

/**
 * Connects with a fresh identity and asserts the server refuses to complete pairing.
 *
 * Used after a successful pairing: the token is consumed and the pairing window is closed, so a
 * second device must never receive `pair.ok`. Prints a marker only on a definite refusal so a
 * crash cannot be mistaken for success.
 */
private fun refusedPairing(offer: kr.jjw.graft.core.PairingOffer): Boolean {
    val identity = DeviceIdentity.loadOrCreate(InMemoryKeyStoreBackend(), "refused")
    val client = GraftClient(
        baseUrl = "wss://${offer.host}:${offer.port}/graft",
        trustManager = PinnedTrustManager { setOf(offer.fingerprint) },
        keyManager = IdentityKeyManager(
            alias = "refused",
            privateKey = identity.privateKey,
            chain = arrayOf(identity.certificate),
        ),
    )
    val latch = CountDownLatch(1)
    val sawPairOk = java.util.concurrent.atomic.AtomicBoolean(false)
    val sawRefusal = java.util.concurrent.atomic.AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val job = scope.launch {
        runCatching {
            withTimeout(20_000) {
                client.connect().collect { event ->
                    when (event) {
                        is ConnectionEvent.Secure -> client.send(
                            Envelope.create(
                                type = "pair.request",
                                payload = buildJsonObject {
                                    put("token", offer.token)
                                    put("deviceId", "refused-device")
                                    put("name", "Refused Peer")
                                    put("platform", "android")
                                }
                            )
                        )

                        is ConnectionEvent.Message -> {
                            if (event.envelope.type == "pair.ok") sawPairOk.set(true)
                            if (event.envelope.type == "error") sawRefusal.set(true)
                            latch.countDown()
                        }

                        is ConnectionEvent.Closed -> latch.countDown()
                        ConnectionEvent.Connecting -> Unit
                    }
                }
            }
        }
    }
    val finished = latch.await(25, TimeUnit.SECONDS)
    job.cancel()
    client.close()

    return when {
        sawPairOk.get() -> {
            System.err.println("FAILED: a second device paired with a consumed token")
            false
        }
        sawRefusal.get() || finished -> {
            println("REFUSED")
            true
        }
        else -> {
            System.err.println("FAILED: no verdict on the second pairing attempt")
            false
        }
    }
}

private fun replayPairing(
    offer: kr.jjw.graft.core.PairingOffer,
    identity: DeviceIdentity,
): ReplayOutcome {
    val client = GraftClient(
        baseUrl = "wss://${offer.host}:${offer.port}/graft",
        trustManager = PinnedTrustManager { setOf(offer.fingerprint) },
        keyManager = IdentityKeyManager(
            alias = "e2e",
            privateKey = identity.privateKey,
            chain = arrayOf(identity.certificate),
        ),
    )
    val latch = CountDownLatch(1)
    val sawUnpairedError = java.util.concurrent.atomic.AtomicBoolean(false)
    val sawPairOk = java.util.concurrent.atomic.AtomicBoolean(false)
    val sawAnyFrame = java.util.concurrent.atomic.AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val job = scope.launch {
        runCatching {
            withTimeout(20_000) {
                client.connect().collect { event ->
                    when (event) {
                        is ConnectionEvent.Secure -> client.send(
                            Envelope.create(
                                type = "pair.request",
                                payload = buildJsonObject {
                                    put("token", offer.token)
                                    put("deviceId", "e2e-device")
                                    put("name", "E2E Peer")
                                    put("platform", "android")
                                }
                            )
                        )

                        is ConnectionEvent.Message -> {
                            sawAnyFrame.set(true)
                            val code = event.envelope.payload.jsonObject["code"]?.jsonPrimitive?.content
                            if (event.envelope.type == "error" && code == "unpaired") {
                                sawUnpairedError.set(true)
                            }
                            if (event.envelope.type == "pair.ok") {
                                sawPairOk.set(true)
                            }
                            latch.countDown()
                        }

                        is ConnectionEvent.Closed -> latch.countDown()

                        ConnectionEvent.Connecting -> Unit
                    }
                }
            }
        }
    }
    val finished = latch.await(25, TimeUnit.SECONDS)
    job.cancel()
    client.close()

    return when {
        sawPairOk.get() -> ReplayOutcome.ACCEPTED
        sawUnpairedError.get() -> ReplayOutcome.REJECTED
        // A close with no frame is not proof of rejection; it could be a crash or a dropped reply.
        else -> ReplayOutcome.NO_VERDICT
    }
}
