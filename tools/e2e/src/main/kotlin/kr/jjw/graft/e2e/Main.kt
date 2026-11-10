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
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--uri" -> uri = args.getOrNull(++index)
            else -> Unit
        }
        index++
    }

    if (uri == null) {
        System.err.println("usage: --uri <pairing-uri>")
        exitProcess(2)
    }

    val offer = PairingUri.parse(uri)
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
    val replayRejected = replayPairing(offer, identity)
    if (!replayRejected) {
        System.err.println("FAILED: the server accepted a replayed pairing token")
        exitProcess(1)
    }
    println("REPLAY_REJECTED")

    // Gradle's non-daemon threads keep the JVM alive for a minute; exit explicitly.
    exitProcess(0)
}

private fun replayPairing(
    offer: kr.jjw.graft.core.PairingOffer,
    identity: DeviceIdentity,
): Boolean {
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
    val rejected = java.util.concurrent.atomic.AtomicBoolean(false)
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
                            val payload = event.envelope.payload.jsonObject
                            val code = payload["code"]?.jsonPrimitive?.content
                            if (event.envelope.type == "error" && code == "unpaired") {
                                rejected.set(true)
                            }
                            if (event.envelope.type == "pair.ok") rejected.set(false)
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
    return finished && rejected.get()
}
