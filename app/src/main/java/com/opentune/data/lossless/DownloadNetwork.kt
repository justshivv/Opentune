package com.opentune.data.lossless

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

internal data class DownloadNetworkState(val connected: Boolean = false, val metered: Boolean = true) {
    val label get() = if (!connected) "Offline" else if (metered) "Metered network / mobile data" else "Unmetered network"
}
internal object DownloadNetwork {
    val state = MutableStateFlow(DownloadNetworkState())
    fun init(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            state.value = DownloadNetworkState(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), cm.isActiveNetworkMetered)
        }
        refresh()
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        })
    }
    fun waitingReason(entry: LosslessEntry, network: DownloadNetworkState, workerRunning: Boolean = false): String = when {
        !network.connected -> "Waiting for an internet connection"
        entry.unmeteredOnly && network.metered -> "Waiting for Wi-Fi or another unmetered network"
        workerRunning -> "Waiting for the active download to finish"
        else -> "Waiting for Android to start the download"
    }
}
