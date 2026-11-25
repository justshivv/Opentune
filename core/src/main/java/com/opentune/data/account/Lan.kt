package com.opentune.data.account

import java.io.IOException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The home network: which addresses count as on it, which of this device's own addresses to
 * show a phone (best first), and reaching another device on it by whichever of its addresses
 * answers. Nothing here looks a name up or sends anything off the network.
 */
object Lan {
    /** Tests run both ends on this machine, so they may allow its own address. */
    @Volatile internal var allowLoopback = false

    /**
     * Milliseconds on this device's steady clock, which never jumps when the wall clock is set:
     * what a code's two minutes are timed by, on each end separately.
     */
    fun steadyMs(): Long = System.nanoTime() / 1_000_000

    /** A code that points somewhere other than the home network: [host] is where. */
    class OffHomeNetwork(val host: String) : IllegalArgumentException("not on the home network")

    /**
     * IPv4 that a home network hands out: 10/8, 172.16/12 and 192.168/16, the carrier-grade
     * 100.64/10 some routers and mesh systems use, and link-local 169.254/16 (two devices with
     * no router between them), but not 169.254.169.254, which is a cloud server's metadata
     * address and never a TV. Written plainly (no leading zeros), as an address, never a name.
     */
    fun ipv4(host: String): Boolean {
        val ip = octets(host) ?: return false
        return ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 100 && ip[1] in 64..127) ||
            (ip[0] == 169 && ip[1] == 254 && !(ip[2] == 169 && ip[3] == 254))
    }

    /** The private ranges every NepTune has accepted: what an older phone takes as a code's main address. */
    fun classicIpv4(host: String): Boolean {
        val ip = octets(host) ?: return false
        return ip[0] == 10 || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 192 && ip[1] == 168)
    }

    private fun octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return null
        return parts.map { it.toInt() }.takeIf { ip -> ip.all { it in 0..255 } }
    }

    private val V6 = Regex("[0-9A-Fa-f:]{2,39}(%[A-Za-z0-9_.-]{1,32})?")

    /**
     * IPv6 on the home network: unique local (fc00::/7) and link-local (fe80::/10, optionally
     * with the interface it's on after a `%`).
     */
    fun ipv6(host: String): Boolean = v6(host) != null

    private fun v6(host: String): Inet6Address? {
        if (!V6.matches(host) || ':' !in host) return null
        val bare = host.substringBefore('%')
        // A literal with ':' is never looked up as a name.
        val a = runCatching { InetAddress.getByName(bare) }.getOrNull() as? Inet6Address ?: return null
        val b = a.address
        val ula = (b[0].toInt() and 0xfe) == 0xfc
        val linkLocal = (b[0].toInt() and 0xff) == 0xfe && (b[1].toInt() and 0xc0) == 0x80
        return a.takeIf { ula || linkLocal }
    }

    private fun linkLocal6(a: Inet6Address) = a.isLinkLocalAddress

    /** Whether [host] is an address on the home network (and not a name, or anywhere on the internet). */
    fun accepts(host: String): Boolean = ipv4(host) || ipv6(host) || (allowLoopback && host == "127.0.0.1")

    /** [address] (one this device holds or sees), as the text [accepts] takes; null when it's off the home network. */
    fun text(address: InetAddress): String? {
        val text = when (address) {
            is Inet6Address -> {
                val plain = address.hostAddress.orEmpty().substringBefore('%')
                if (address.isLinkLocalAddress) address.scopedInterface?.name?.let { "$plain%$it" } ?: plain else plain
            }
            else -> address.hostAddress.orEmpty()
        }
        return text.takeIf { accepts(it) }
    }

    /**
     * Where to try [host]: the address itself; for a link-local IPv6 address with no interface
     * named (one read from another device's code, whose interface names mean nothing here),
     * that address on each of this device's interfaces that could reach it.
     */
    fun resolve(host: String, interfaces: () -> List<NetworkInterface> = ::upInterfaces): List<InetAddress> {
        if (ipv4(host) || (allowLoopback && host == "127.0.0.1")) return listOfNotNull(runCatching { InetAddress.getByName(host) }.getOrNull())
        val a = v6(host) ?: return emptyList()
        if (!linkLocal6(a)) return listOf(a)
        val scope = host.substringAfter('%', "")
        if (scope.isNotEmpty()) {
            runCatching { InetAddress.getByName(host) }.getOrNull()?.let { return listOf(it) }
        }
        return interfaces()
            .filter { i -> i.inetAddresses.toList().any { it is Inet6Address && it.isLinkLocalAddress } }
            .mapNotNull { i -> runCatching { Inet6Address.getByAddress(null, a.address, i) }.getOrNull() }
            .take(4)
    }

    private fun upInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    }.getOrDefault(emptyList())

    // ---- This device's own addresses ----

    /** One of this device's addresses and what its interface is called. */
    data class Local(val host: String, val name: String, val display: String = name)

    // Interfaces that are not the home network: containers and virtual machines, VPNs and
    // tunnels, Wi-Fi Direct and hotspots (a TV's screen mirroring keeps a p2p one up, often
    // 192.168.49.1), USB and Bluetooth tethering, and the mobile network.
    private val ASIDE_NAME = Regex(
        "^(docker|veth|br-|virbr|vboxnet|vmnet|vnic|vEthernet|tun|tap|utun|ppp|wg|tailscale|zt|ipsec|p2p|ap\\d|softap|swlan|" +
            "rndis|usb|ncm|bt-pan|bnep|llw|awdl|anpi|gif|stf|dummy|ham|clat|v4-|rmnet|ccmni|radio|nan|aware|lxc|lxd|cni|flannel|cali|kube)",
        RegexOption.IGNORE_CASE,
    )
    private val ASIDE_DISPLAY = Regex(
        "virtual|vmware|virtualbox|hyper-v|vethernet|tap-|tunnel|wireguard|tailscale|zerotier|vpn|loopback|bluetooth|docker|wi-fi direct|p2p|hamachi|npcap|teredo",
        RegexOption.IGNORE_CASE,
    )
    // Wi-Fi and Ethernet, as Android, Linux, macOS and Windows (through Java) name them.
    private val HOME_NAME = Regex("^(wlan|wl|eth|en|em|mlan|wifi|ra\\d)", RegexOption.IGNORE_CASE)

    /** Whether [l]'s interface is something other than the home network (a VPN, a container, Wi-Fi Direct…). */
    fun aside(l: Local): Boolean = ASIDE_NAME.containsMatchIn(l.name) || ASIDE_DISPLAY.containsMatchIn(l.display)

    private fun addressRank(host: String): Int {
        val ip = octets(host)
        return when {
            ip == null -> if (host.startsWith("fe80", ignoreCase = true)) 7 else 6
            ip[0] == 192 && ip[1] == 168 -> 0
            ip[0] == 10 -> 1
            ip[0] == 172 -> 2
            ip[0] == 100 -> 3
            else -> 4
        }
    }

    /**
     * [all] in the order a phone should try them: the ones in [preferred] (the system's own
     * choice: the network it sends to the internet through) first, unless they're a VPN, a
     * container or the like; then Wi-Fi and Ethernet before other interfaces, anything aside
     * last; IPv4 before IPv6, and among IPv4 192.168 before 10 before 172.16 (Docker's).
     */
    fun order(all: List<Local>, preferred: List<String> = emptyList()): List<String> {
        val usable = all.filter { accepts(it.host) }.distinctBy { it.host }
        return usable.sortedWith(
            compareBy<Local>(
                { aside(it) },
                { it.host !in preferred },
                { !HOME_NAME.containsMatchIn(it.name) },
                { addressRank(it.host) },
            ),
        ).map { it.host }
    }

    /** This device's home-network addresses, best first. [preferred] as for [order]. */
    fun addresses(preferred: List<String> = listOfNotNull(systemPreferred())): List<String> = order(locals(), preferred)

    /** Every address this device holds on an interface that's up. */
    fun locals(): List<Local> = upInterfaces().flatMap { i ->
        i.inetAddresses.toList().mapNotNull { a -> text(a)?.let { Local(it, i.name.orEmpty(), i.displayName.orEmpty()) } }
    }

    /**
     * The address the system would send from to reach the internet, found by asking it to route
     * a UDP socket (connecting a UDP socket sends nothing). On a computer with containers or
     * virtual machines, that is the real network card rather than a virtual one.
     */
    fun systemPreferred(): String? = runCatching {
        DatagramSocket().use { s ->
            // 192.0.2.1 is a documentation address (RFC 5737): it is routed like any other, nothing is sent to it.
            s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), 9))
            s.localAddress?.let(::text)
        }
    }.getOrNull()

    /** The address to put first in a code: one every phone reads (an older phone takes only 10, 172.16 and 192.168). */
    fun primary(ordered: List<String>): String? = ordered.firstOrNull(::classicIpv4) ?: ordered.firstOrNull(::ipv4)

    // ---- Reaching another device ----

    /** Makes the socket to reach an address with: on a phone, one held to its Wi-Fi rather than mobile data or a VPN. */
    fun interface Sockets {
        fun socket(address: InetAddress): Socket

        companion object {
            val PLAIN = Sockets { Socket() }
        }
    }

    /** How trying every address of a device went, when none of them would do. */
    class Unreached(
        /** Some address answered that nothing listens on that port: the device is there, its code isn't. */
        val refused: Boolean,
        /** Some address never answered at all. */
        val timedOut: Boolean,
        /** The phone has no way to any of them. */
        val noRoute: Boolean,
        /** The last thing a device that answered said, when one did and then hung up or failed the handshake. */
        val rejected: Throwable?,
    ) : IOException("Couldn't reach the device")

    /**
     * Connects to every one of [targets] at once (each a little after the one before it, so the
     * first is favoured), and hands each connection that opens, in the order they open, to
     * [attempt] until one returns. The connections not used are closed. Throws [Unreached] when
     * none worked; an [attempt] that throws [Definitive] ends it at once with that.
     */
    fun <T> reach(
        targets: List<InetSocketAddress>,
        sockets: Sockets = Sockets.PLAIN,
        connectMs: Int = CONNECT_MS,
        staggerMs: Long = STAGGER_MS,
        attempt: (Socket) -> T,
    ): T {
        require(targets.isNotEmpty())
        val done = AtomicBoolean(false)
        val opened = LinkedBlockingQueue<Any>()
        val threads = targets.mapIndexed { i, target ->
            Thread({
                try {
                    if (i > 0) Thread.sleep(staggerMs * i)
                    if (done.get()) { opened.put(Skipped); return@Thread }
                    val socket = sockets.socket(target.address)
                    try {
                        socket.connect(target, connectMs)
                    } catch (e: Throwable) {
                        runCatching { socket.close() }; throw e
                    }
                    if (done.get()) { runCatching { socket.close() }; opened.put(Skipped) } else {
                        opened.put(socket)
                        // Finished meanwhile: take it back and close it, unless it was already taken.
                        if (done.get() && opened.remove(socket)) runCatching { socket.close() }
                    }
                } catch (_: InterruptedException) {
                    opened.put(Skipped)
                } catch (e: Throwable) {
                    opened.put(e)
                }
            }, "lan-reach").apply { isDaemon = true; start() }
        }
        var refused = false
        var timedOut = false
        var noRoute = false
        var rejected: Throwable? = null
        try {
            repeat(targets.size) {
                val r = opened.poll(connectMs + staggerMs * targets.size + 1_000, TimeUnit.MILLISECONDS) ?: run { timedOut = true; return@repeat }
                when (r) {
                    is Socket -> try {
                        return attempt(r).also { done.set(true) }
                    } catch (e: Definitive) {
                        done.set(true); throw e
                    } catch (e: Exception) {
                        rejected = e
                    } finally {
                        runCatching { r.close() }
                    }
                    is NoRouteToHostException -> noRoute = true
                    is ConnectException -> if (r.message?.contains("unreachable", true) == true) noRoute = true else refused = true
                    is SocketTimeoutException -> timedOut = true
                    is IOException -> if (r.message?.contains("unreachable", true) == true) noRoute = true else timedOut = true
                    Skipped -> Unit
                    else -> timedOut = true
                }
            }
        } finally {
            done.set(true)
            threads.forEach { it.interrupt() }
            // Anything that opened after the winner is closed rather than left to the device.
            generateSequence { opened.poll() }.forEach { (it as? Socket)?.let { s -> runCatching { s.close() } } }
        }
        throw Unreached(refused, timedOut, noRoute, rejected)
    }

    /** Thrown by an attempt for an answer that holds for every address: the device said no. */
    open class Definitive(message: String? = null) : IOException(message)

    private object Skipped

    const val CONNECT_MS = 6_000
    const val STAGGER_MS = 250L
}
