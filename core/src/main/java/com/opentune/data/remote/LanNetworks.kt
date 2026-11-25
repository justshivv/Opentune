package com.opentune.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.opentune.data.account.Lan
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/**
 * The home network as Android sees it: which of this device's networks are Wi-Fi or Ethernet
 * (rather than mobile data, a VPN or Wi-Fi Direct), to pick the address a TV shows in its code,
 * to hold a phone's socket to its Wi-Fi when mobile data or a VPN would otherwise carry it, and
 * to say plainly why a TV couldn't be reached.
 */
object LanNetworks {
    private class Home(val network: Network, val interfaceName: String, val addresses: List<Pair<InetAddress, Int>>, val wifi: Boolean)

    private fun manager(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private fun networks(cm: ConnectivityManager): List<Network> = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())

    private fun homes(cm: ConnectivityManager): List<Home> = networks(cm).mapNotNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !(wifi || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return@mapNotNull null
        val links = cm.getLinkProperties(n) ?: return@mapNotNull null
        Home(n, links.interfaceName.orEmpty(), links.linkAddresses.map { it.address to it.prefixLength }, wifi)
    }

    /**
     * This device's home-network addresses, best first: those on its Wi-Fi and Ethernet
     * networks (the one it uses for the internet first), then any other the system lists;
     * never Wi-Fi Direct (which a TV keeps up for screen mirroring), a hotspot, a VPN or
     * mobile data ahead of them.
     */
    fun ownAddresses(context: Context): List<String> {
        val cm = manager(context) ?: return Lan.addresses()
        val homes = homes(cm).sortedByDescending { it.network == cm.activeNetwork }
        val fromSystem = homes.flatMap { h -> h.addresses.mapNotNull { (a, _) -> Lan.text(a)?.let { Lan.Local(it, h.interfaceName) } } }
        val preferred = fromSystem.map { it.host }
        return Lan.order(fromSystem + Lan.locals(), preferred)
    }

    /** What this phone's networks are like, for saying why a TV at [hosts] couldn't be reached. */
    data class Status(
        /** On Wi-Fi or Ethernet at all. */
        val onLan: Boolean,
        /** A VPN is on. */
        val vpn: Boolean,
        /** False when none of [hosts] is on this phone's own Wi-Fi subnet; null when that can't be told. */
        val sameSubnet: Boolean?,
    )

    fun status(context: Context, hosts: List<String>): Status {
        val cm = manager(context) ?: return Status(onLan = true, vpn = false, sameSubnet = null)
        val vpn = networks(cm).any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val homes = homes(cm)
        val targets = hosts.mapNotNull { h -> runCatching { Lan.resolve(h) { emptyList() }.firstOrNull() }.getOrNull() }.filterIsInstance<Inet4Address>()
        val subnets = homes.flatMap { it.addresses }.filter { it.first is Inet4Address }
        val same = if (targets.isEmpty() || subnets.isEmpty()) null else targets.any { t -> subnets.any { (a, p) -> within(t, a, p) } }
        return Status(onLan = homes.isNotEmpty(), vpn = vpn, sameSubnet = same)
    }

    /** Whether [address] is in the subnet of [own]/[prefix]. */
    internal fun within(address: InetAddress, own: InetAddress, prefix: Int): Boolean {
        val a = address.address
        val b = own.address
        if (a.size != b.size || prefix !in 0..a.size * 8) return false
        var bits = prefix
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /**
     * Sockets held to the Wi-Fi or Ethernet network that [Socket]'s address is on (else to Wi-Fi),
     * when the system would otherwise send them over mobile data or a VPN. Where Android won't
     * allow that (a VPN set to block everything else), the socket goes the usual way.
     */
    fun sockets(context: Context): Lan.Sockets = Lan.Sockets { address ->
        val socket = Socket()
        val cm = manager(context)
        if (cm != null) {
            val homes = homes(cm)
            val home = homes.firstOrNull { h -> h.addresses.any { (a, p) -> within(address, a, p) } }
                ?: homes.firstOrNull { it.wifi } ?: homes.firstOrNull()
            if (home != null && home.network != cm.activeNetwork) runCatching { home.network.bindSocket(socket) }
        }
        socket
    }
}
