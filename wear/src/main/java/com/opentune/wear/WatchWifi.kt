package com.opentune.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Wi-Fi for downloads. Wear OS keeps Wi-Fi off while the watch talks to the phone over
 * Bluetooth, and a song over Bluetooth takes minutes; so while a download waits the watch asks
 * for Wi-Fi itself, and the app's connections go over it while it's up. With nothing left to
 * download it lets go, and Wear OS may turn Wi-Fi off again to save the battery.
 */
object WatchWifi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _network = MutableStateFlow<Network?>(null)
    /** The Wi-Fi network held now, if any. */
    val network: StateFlow<Network?> = _network.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Whether downloads may run now. */
    fun ready(): Boolean = _network.value != null

    fun init(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        scope.launch {
            Downloads.entries.map { m -> m.values.any { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING } }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted) hold(app, cm) else release(cm) }
        }
    }

    private fun hold(context: Context, cm: ConnectivityManager) {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                _network.value = network
                // The relays' connections were on the other network: Connect reaches them again now.
                com.opentune.data.connect.Connect.wake()
                // Downloads that waited for Wi-Fi go now rather than at their next retry.
                scope.launch { runCatching { Downloads.requeueWaiting(context) } }
            }

            override fun onLost(network: Network) {
                if (_network.value == network) {
                    cm.bindProcessToNetwork(null)
                    _network.value = null
                    com.opentune.data.connect.Connect.wake()
                }
            }
        }
        callback = cb
        runCatching { cm.requestNetwork(request, cb) }.onFailure { callback = null }
    }

    private fun release(cm: ConnectivityManager) {
        val cb = callback ?: return
        callback = null
        runCatching { cm.unregisterNetworkCallback(cb) }
        cm.bindProcessToNetwork(null)
        _network.value = null
        com.opentune.data.connect.Connect.wake()
    }
}
