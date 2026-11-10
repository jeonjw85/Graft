package kr.jjw.graft.core.transport

import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kr.jjw.graft.core.Envelope
import kr.jjw.graft.core.MessageCodec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionEvent {
    data object Connecting : ConnectionEvent
    data class Secure(val serverFingerprint: String) : ConnectionEvent
    data class Message(val envelope: Envelope) : ConnectionEvent
    data class Closed(val reason: TransportCloseReason) : ConnectionEvent
}

enum class TransportCloseReason {
    PIN_MISMATCH,
    NETWORK,
    NORMAL,
    UNKNOWN,
}

/**
 * A mutually authenticated WebSocket client that pins the server's SPKI fingerprint.
 *
 * @param baseUrl `wss://host:port/graft`
 */
class GraftClient(
    private val baseUrl: String,
    private val trustManager: PinnedTrustManager,
    private val keyManager: IdentityKeyManager,
    private val pingIntervalMillis: Long = 20_000,
) {
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    /** Set by the trust manager when the server chain is verified, read on open. */
    internal val lastVerifiedServerFingerprint = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun connect(): Flow<ConnectionEvent> = callbackFlow {
        trySend(ConnectionEvent.Connecting)

        // Each attempt gets a clean rejection record; a stale flag from an earlier connection must
        // not turn a transient network error into a permanent "re-pair" instruction.
        trustManager.beginAttempt()

        trustManager.onServerVerified = { lastVerifiedServerFingerprint.set(it) }

        val okHttp = OkHttpClient.Builder()
            .sslSocketFactory(socketFactory(), trustManager)
            // The certificate is pinned by SPKI and the QR code carries the IP address, so the
            // hostname has no trust meaning here. Pinning is what authenticates the server.
            .hostnameVerifier { _, _ -> true }
            .pingInterval(pingIntervalMillis, TimeUnit.MILLISECONDS)
            .build()
        client = okHttp

        val request = Request.Builder().url(baseUrl).build()
        val socket = okHttp.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // The handshake stores the peer certificate as seen by the socket; OkHttp may
                    // expose an empty list through the response, so fall back to the last pinned
                    // fingerprint the trust manager verified.
                    val certificate = response.handshake?.peerCertificates?.firstOrNull()
                        as? java.security.cert.X509Certificate
                    val fingerprint = certificate?.let(PinnedTrustManager::fingerprintOf)
                        ?: lastVerifiedServerFingerprint.get()
                        ?: ""
                    trySend(ConnectionEvent.Secure(fingerprint))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = runCatching {
                        MessageCodec.decode(text.toByteArray(Charsets.UTF_8))
                    }.getOrNull() ?: return
                    trySend(ConnectionEvent.Message(envelope))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Only a rejection by our own trust manager means the pin failed. Any other TLS
                    // error (expired certificate, server refusing our certificate, a transient
                    // alert) must stay retryable, or a user could never recover.
                    val reason = if (trustManager.lastRejectionWasPin) {
                        TransportCloseReason.PIN_MISMATCH
                    } else {
                        TransportCloseReason.NETWORK
                    }
                    trySend(ConnectionEvent.Closed(reason))
                    shutdown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    trySend(ConnectionEvent.Closed(TransportCloseReason.NORMAL))
                    shutdown()
                }
            }
        )
        webSocket = socket

        awaitClose {
            socket.cancel()
            shutdown()
        }
    }

    fun send(envelope: Envelope): Boolean {
        val text = MessageCodec.encode(envelope).toString(Charsets.UTF_8)
        return webSocket?.send(text) ?: false
    }

    fun close() {
        webSocket?.close(1000, null)
        webSocket = null
        shutdown()
    }

    private fun shutdown() {
        // Eviction is enough; shutting the shared dispatcher's executor down races with OkHttp's
        // own shutdown and surfaces as "Gave up waiting for queue to shut down" in tests.
        client?.connectionPool?.evictAll()
        client?.dispatcher?.cancelAll()
        client = null
    }

    private fun socketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf<KeyManager>(keyManager),
            arrayOf<X509TrustManager>(trustManager),
            null
        )
        return context.socketFactory
    }
}
