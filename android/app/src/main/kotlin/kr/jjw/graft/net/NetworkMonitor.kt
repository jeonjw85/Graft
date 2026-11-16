package kr.jjw.graft.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Reports when a usable network appears.
 *
 * Reconnecting on an exponential backoff can keep a phone waiting a minute after the Wi-Fi comes
 * back; a network callback lets the service retry immediately instead.
 */
class NetworkMonitor(context: Context, private val onAvailable: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailable()
        }
    }

    fun start() {
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
