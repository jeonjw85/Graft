package kr.jjw.graft.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kr.jjw.graft.core.BonjourServiceInfo

/** A Mac found on the LAN. */
data class DiscoveredHost(
    val host: String,
    val port: Int,
    val fingerprint: String,
)

/**
 * Finds Graft Macs with Android's NSD implementation.
 *
 * The advertised fingerprint is read from the TXT record; a service whose fingerprint cannot be
 * read is reported with an empty value so the caller can ignore it rather than trust the name.
 */
class BonjourDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var resolveListener: NsdManager.ResolveListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (DiscoveredHost) -> Unit, onFailure: (String) -> Unit = {}) {
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != BonjourServiceInfo.SERVICE_TYPE) return
                resolve(service, onFound, onFailure)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onFailure("Discovery failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        discoveryListener = discovery
        nsdManager.discoverServices(BonjourServiceInfo.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun resolve(
        service: NsdServiceInfo,
        onFound: (DiscoveredHost) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val fingerprint = resolved.attributes?.get("fp")?.toString(Charsets.UTF_8) ?: ""
                onFound(
                    DiscoveredHost(
                        host = host,
                        port = resolved.port,
                        fingerprint = fingerprint,
                    )
                )
            }
        }
        resolveListener = listener
        runCatching { nsdManager.resolveService(service, listener) }
            .onFailure { onFailure("Could not resolve the Mac's address") }
    }

    /**
     * Searches the LAN for the Mac this phone trusts.
     *
     * Bounded on purpose: discovery is a fallback for a changed address, not a replacement for the
     * saved one. A host is returned only when its advertised fingerprint matches [pinnedFingerprint]
     * (or starts with [prefix] before the first pairing).
     */
    suspend fun find(
        pinnedFingerprint: String?,
        prefix: String?,
        timeoutMillis: Long = 4_000,
    ): DiscoveredHost? {
        stop()
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                start(
                    onFound = { host ->
                        if (ReconnectTarget.match(pinnedFingerprint, prefix, listOf(host)) != null &&
                            delivered.compareAndSet(false, true)
                        ) {
                            stop()
                            continuation.resume(host)
                        }
                    },
                    onFailure = { /* keep waiting until the timeout */ },
                )
                continuation.invokeOnCancellation { stop() }
            }
        }
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        resolveListener = null
    }
}
