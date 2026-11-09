package kr.jjw.graft.core.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(okhttp3.ExperimentalOkHttpApi::class)
class GraftClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var server: MockWebServer
    private lateinit var serverCertificate: HeldCertificate
    private lateinit var clientCertificate: HeldCertificate

    @BeforeEach
    fun setUp() {
        serverCertificate = HeldCertificate.Builder()
            .commonName("graft-gateway")
            .build()
        clientCertificate = HeldCertificate.Builder()
            .commonName("graft-phone")
            .build()

        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.requestClientAuth()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""{"v":1,"id":"reply","re":"request","type":"hello.ok","ts":1,"payload":{}}""")
                    }
                })
                .build()
        )
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `connects and exchanges a control frame`() = runBlocking {
        val client = clientAccepting(serverCertificate)

        val events = mutableListOf<ConnectionEvent>()
        val latch = CountDownLatch(1)
        val job = scope.launch {
            client.connect().collect { event ->
                events += event
                if (event is ConnectionEvent.Secure) {
                    client.send(
                        kr.jjw.graft.core.Envelope.create(
                            type = "hello",
                            payload = kotlinx.serialization.json.buildJsonObject { }
                        )
                    )
                }
                if (event is ConnectionEvent.Message) latch.countDown()
            }
        }

        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "no reply: $events")

            assertTrue(events.any { it is ConnectionEvent.Secure })
            val message = events.filterIsInstance<ConnectionEvent.Message>().first()
            assertEquals("hello.ok", message.envelope.type)
        } finally {
            job.cancel()
            client.close()
        }
    }

    @Test
    fun `rejects a server certificate that is not pinned`() = runBlocking {
        val client = clientWithUnrelatedPin()

        val events = mutableListOf<ConnectionEvent>()
        val latch = CountDownLatch(1)
        val job = scope.launch {
            client.connect().collect { event ->
                events += event
                if (event is ConnectionEvent.Closed) latch.countDown()
            }
        }

        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "pinning did not fail the connection: $events")
        } finally {
            job.cancel()
            client.close()
        }
    }

    private fun clientWithUnrelatedPin(): GraftClient {
        val stranger = HeldCertificate.Builder().commonName("stranger").build()
        val trust = PinnedTrustManager { setOf(PinnedTrustManager.fingerprintOf(stranger.certificate)) }
        val keys = IdentityKeyManager(
            alias = "graft",
            privateKey = clientCertificate.keyPair.private,
            chain = arrayOf(clientCertificate.certificate),
        )
        val url = server.url("/graft").toString().replace("http://", "wss://")
        return GraftClient(url, trust, keys)
    }

    private fun clientAccepting(expected: HeldCertificate): GraftClient {
        val fingerprint = PinnedTrustManager.fingerprintOf(expected.certificate)
        val trust = PinnedTrustManager { setOf(fingerprint) }
        val keys = IdentityKeyManager(
            alias = "graft",
            privateKey = clientCertificate.keyPair.private,
            chain = arrayOf(clientCertificate.certificate),
        )
        val url = server.url("/graft").toString().replace("http://", "wss://")
        return GraftClient(url, trust, keys)
    }
}
