package com.opentune.data

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone is on Wi-Fi (or another unmetered network), seeing through a VPN.
 *
 * Android marks a VPN metered unless the VPN app says otherwise, and most don't, so
 * with a VPN always on the phone looks like it's on mobile data even at home: Wi-Fi-only
 * downloads waited for ever and streams took the mobile quality. When the active network
 * is a VPN, what counts is the network it runs over, which Android keeps on Wi-Fi
 * whenever Wi-Fi is connected and working.
 */
object UnmeteredNetwork {
    /** On an unmetered network now, directly or through a VPN. */
    fun available(cm: ConnectivityManager): Boolean = unmeteredConnection(active(cm), others(cm))

    /**
     * On Wi-Fi only through a VPN that calls itself metered. Android's own "unmetered"
     * check (the one WorkManager waits on) never passes then, so Wi-Fi-only work is
     * scheduled for any connection and checks the network itself when it starts.
     */
    fun behindMeteredVpn(cm: ConnectivityManager): Boolean {
        val active = active(cm) ?: return false
        return active.vpn && !active.unmetered && unmeteredConnection(active, others(cm))
    }

    private fun active(cm: ConnectivityManager): NetworkFacts? =
        cm.activeNetwork?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }?.let(::facts)

    @Suppress("DEPRECATION") // allNetworks is the one way to see the network a VPN runs over.
    private fun others(cm: ConnectivityManager): List<NetworkFacts> =
        runCatching { cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it)?.let(::facts) } }.getOrDefault(emptyList())

    private fun facts(caps: NetworkCapabilities) = NetworkFacts(
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}

internal data class NetworkFacts(val vpn: Boolean, val unmetered: Boolean, val validated: Boolean)

/**
 * The decision behind [UnmeteredNetwork.available]: the active network is unmetered,
 * or it is a VPN while a working unmetered network (the one under it) is connected.
 */
internal fun unmeteredConnection(active: NetworkFacts?, networks: List<NetworkFacts>): Boolean = when {
    active == null -> false
    active.unmetered -> true
    !active.vpn -> false
    else -> networks.any { !it.vpn && it.unmetered && it.validated }
}
