package com.opentune.data.account

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which addresses count as the home network, which of a TV's own it shows first, and trying them all. */
class LanTest {
    private fun l(host: String, name: String, display: String = name) = Lan.Local(host, name, display)

    @Test fun homeAddressesAndNothingElse() {
        listOf(
            "192.168.1.20", "10.0.0.5", "172.16.4.4", "172.31.255.254", "100.64.0.9", "100.127.1.1", "169.254.3.3",
            "fd12:3456:789a::1", "fe80::1c2b:3cff:fe4d:5e6f", "fe80::1%wlan0",
        ).forEach { assertTrue(it, Lan.accepts(it)) }
        listOf(
            "8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "169.254.169.254", "127.0.0.1", "0.0.0.0",
            "192.168.1.001", "192.168.1", "tv.local", "localhost", "2001:4860:4860::8888", "::1", "::ffff:8.8.8.8", "",
        ).forEach { assertFalse(it, Lan.accepts(it)) }
        // An older phone reads only the classic private ranges as a code's main address.
        assertTrue(Lan.classicIpv4("192.168.0.2"))
        assertFalse(Lan.classicIpv4("100.64.0.9"))
    }

    @Test fun aTvShowsItsWifiOrEthernetAddressNotWifiDirectOrAVpn() {
        // A Mi box as it lists them: Wi-Fi Direct (kept up for screen mirroring) first, a VPN, Docker-ish bridges, then Wi-Fi.
        val all = listOf(
            l("192.168.49.1", "p2p-wlan0-0"),
            l("10.8.0.2", "tun0"),
            l("172.17.0.1", "docker0"),
            l("fe80::1%wlan0", "wlan0"),
            l("192.168.1.20", "wlan0"),
        )
        val order = Lan.order(all)
        assertEquals("192.168.1.20", order.first())
        assertEquals(listOf("192.168.49.1", "10.8.0.2", "172.17.0.1").toSet(), order.takeLast(3).toSet())
        assertEquals("192.168.1.20", Lan.primary(order))
    }

    @Test fun theSystemsOwnChoiceComesFirstAmongHomeInterfaces() {
        // Ethernet and Wi-Fi both up; the system sends through Ethernet.
        val all = listOf(l("192.168.1.20", "wlan0"), l("192.168.1.31", "eth0"))
        assertEquals(listOf("192.168.1.31", "192.168.1.20"), Lan.order(all, preferred = listOf("192.168.1.31")))
        assertEquals(listOf("192.168.1.20", "192.168.1.31"), Lan.order(all, preferred = listOf("192.168.1.20")))
        // But never a VPN, even when it's the default route.
        assertEquals("192.168.1.20", Lan.order(all + l("10.8.0.2", "tun0"), preferred = listOf("10.8.0.2")).first())
    }

    @Test fun aComputersVirtualAdaptersGoLast() {
        val all = listOf(
            l("192.168.56.1", "eth3", "VirtualBox Host-Only Ethernet Adapter"),
            l("172.29.0.1", "eth5", "vEthernet (WSL)"),
            l("192.168.0.14", "wlan2", "Intel(R) Wi-Fi 6 AX201"),
        )
        assertEquals("192.168.0.14", Lan.order(all).first())
    }

    @Test fun ipv4BeforeIpv6AndAClassicAddressGoesInTheCode() {
        val all = listOf(l("fd00::5", "wlan0"), l("100.64.1.2", "wlan0"), l("10.0.0.7", "eth0"))
        val order = Lan.order(all)
        assertEquals("10.0.0.7", order.first())
        assertEquals("fd00::5", order.last())
        assertEquals("10.0.0.7", Lan.primary(order))
        // Only a carrier-grade address: it goes in, for phones that read it.
        assertEquals("100.64.1.2", Lan.primary(listOf("fd00::5", "100.64.1.2")))
        assertNull(Lan.primary(listOf("fd00::5")))
    }

    @Test fun aLinkLocalAddressFromAnotherDeviceIsTriedOnThisOnesInterfaces() {
        assertTrue(Lan.resolve("fe80::1", interfaces = { emptyList() }).isEmpty())
        assertEquals(listOf(InetAddress.getByName("192.168.1.20")), Lan.resolve("192.168.1.20"))
        assertTrue(Lan.resolve("8.8.8.8").isEmpty())
        assertTrue(Lan.resolve("tv.local").isEmpty())
    }

    @Test fun reachTakesTheFirstThatAnswersInOrder() {
        Lan.allowLoopback = true
        try {
            ServerSocket(0).use { a ->
                ServerSocket(0).use { b ->
                    val loop = InetAddress.getByName("127.0.0.1")
                    val closed = ServerSocket(0).use { it.localPort }
                    // The first one tried has nothing listening; the next two do, and the earlier is taken.
                    val got = Lan.reach(
                        listOf(InetSocketAddress(loop, closed), InetSocketAddress(loop, a.localPort), InetSocketAddress(loop, b.localPort)),
                        staggerMs = 150,
                    ) { it.port }
                    assertEquals(a.localPort, got)
                }
            }
            // An attempt that fails on one moves on to the next.
            ServerSocket(0).use { a ->
                ServerSocket(0).use { b ->
                    val loop = InetAddress.getByName("127.0.0.1")
                    val got = Lan.reach(listOf(InetSocketAddress(loop, a.localPort), InetSocketAddress(loop, b.localPort)), staggerMs = 100) {
                        if (it.port == a.localPort) error("not the TV") else it.port
                    }
                    assertEquals(b.localPort, got)
                }
            }
            // None of them: says it was refused, not that it timed out.
            val closed = ServerSocket(0).use { it.localPort }
            val e = runCatching { Lan.reach(listOf(InetSocketAddress(InetAddress.getByName("127.0.0.1"), closed))) { } }.exceptionOrNull() as Lan.Unreached
            assertTrue(e.refused)
            assertFalse(e.timedOut)
        } finally {
            Lan.allowLoopback = false
        }
    }
}
