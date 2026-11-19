package com.opentune.data.account

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing's optional second packet, over real loopback sockets, against both ends as
 * every earlier OpenTune wrote them (the [oldReceiver] and [oldSender] below are that code).
 */
class PairingExtrasTest {
    private val cookie = "SAPISID=abc; HSID=def"
    private val token = "mfa.NotARealToken_0123456789-abcdef"
    private val both = PairingExtras(
        PairingExtras.Discord(token),
        PairingExtras.LastFm("0123456789abcdef0123456789abcdef", "shiv", apiKey = "a".repeat(32)),
    )

    /** Runs [device] on one end of a loopback connection and [phone] on the other. */
    private fun <D, P> connect(device: (Socket) -> D, phone: (Socket) -> P): Pair<D, P> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val served = CompletableFuture.supplyAsync { server.accept().use(device) }
            val sent = Socket(InetAddress.getLoopbackAddress(), server.localPort).use(phone)
            return served.get(10, TimeUnit.SECONDS) to sent
        }
    }

    private fun DataOutputStream.packet(b: ByteArray) { writeInt(b.size); write(b); flush() }
    private fun DataInputStream.packet(): ByteArray = ByteArray(readInt()).also(::readFully)

    /** The receiver as it was: the cookie, the ack, then closed. */
    private fun oldReceiver(key: ByteArray, socket: Socket): String {
        val input = DataInputStream(socket.getInputStream())
        val got = String(PairingCrypto.open(key, input.packet(), "session"))
        DataOutputStream(socket.getOutputStream()).packet(PairingCrypto.seal(key, "signed-in".toByteArray(), "ack"))
        return got
    }

    /** The sender as it was: the cookie, waits for the ack, then closes. */
    private fun oldSender(key: ByteArray, socket: Socket) {
        DataOutputStream(socket.getOutputStream()).packet(PairingCrypto.seal(key, cookie.toByteArray(), "session"))
        assertEquals("signed-in", String(PairingCrypto.open(key, DataInputStream(socket.getInputStream()).packet(), "ack")))
    }

    @Test fun extrasRoundTripAndNeverPrintTheirSecrets() {
        val back = PairingExtras.decode(both.encode())
        assertEquals(token, back.discord?.token)
        assertEquals("0123456789abcdef0123456789abcdef", back.lastFm?.session)
        assertEquals("shiv", back.lastFm?.username)
        assertEquals("a".repeat(32), back.lastFm?.apiKey)
        assertNull(back.lastFm?.secret)
        assertFalse(both.toString().contains(token))
        assertEquals(setOf("lastfm"), both.only(setOf("lastfm")).kinds)
        // Malformed parts are dropped, not trusted.
        val odd = PairingExtras.decode("""{"discord":{"token":"short"},"lastfm":{"session":"a b","username":"x"}}""".toByteArray())
        assertTrue(odd.isEmpty)
    }

    @Test fun theOfferAsksInItsLinkAndThePayloadStaysAsOldPhonesReadIt() {
        val offer = PairingOffer.create("192.168.1.42", 40000, 1000, take = setOf("lastfm", "nonsense"))
        assertEquals(setOf("lastfm"), offer.take)
        assertTrue(offer.link.startsWith("opentune://pair/?take=lastfm#"))
        assertEquals(offer.payload, offer.link.substringAfter('#'))
        assertEquals(5, String(PairingCrypto.decode(offer.payload)).split('|').size)
        assertEquals(setOf("lastfm"), PairingOffer.parse(offer.payload, 1001, take = "lastfm").take)
        assertEquals(emptySet<String>(), PairingOffer.parse(offer.payload, 1001).take)
        // A device that asks for nothing shows the code exactly as before.
        assertTrue(PairingOffer.create("192.168.1.42", 40000, 1000).link.startsWith("opentune://pair/#"))
    }

    @Test fun newPhoneHandsANewDeviceOnlyWhatItAskedFor() {
        val key = PairingCrypto.secret()
        var seen: PairingExtras? = null
        var signedIn: String? = null
        val (_, took) = connect(
            { s -> PairingWire.serve(s, key, setOf("lastfm"), { signedIn = it; true }, { e -> seen = e; e.kinds }) },
            { s -> PairingWire.exchange(s, key, cookie, both) },
        )
        assertEquals(cookie, signedIn)
        assertEquals(setOf("lastfm"), took)
        assertNull(seen?.discord) // sent, but this device never asked for it, so it's dropped unread
        assertEquals("shiv", seen?.lastFm?.username)
    }

    @Test fun anOldPhoneSignsANewDeviceInWithoutWaiting() {
        val key = PairingCrypto.secret()
        var signedIn = false
        var asked = false
        val started = System.currentTimeMillis()
        connect(
            { s -> PairingWire.serve(s, key, setOf("lastfm"), { signedIn = true; true }, { asked = true; it.kinds }) },
            { s -> oldSender(key, s) },
        )
        assertTrue(signedIn)
        assertFalse(asked)
        assertTrue(System.currentTimeMillis() - started < PairingWire.EXTRAS_WAIT_MS)
    }

    @Test fun aNewPhoneSignsAnOldDeviceInAndHearsNothingTaken() {
        val key = PairingCrypto.secret()
        val (got, took) = connect({ s -> oldReceiver(key, s) }, { s -> PairingWire.exchange(s, key, cookie, both) })
        assertEquals(cookie, got)
        assertEquals(emptySet<String>(), took)
        // And PairingSender sends nothing at all to a code that didn't ask.
        assertTrue(both.only(PairingOffer.create("10.0.0.2", 40000).take).isEmpty)
    }

    @Test fun aWrongPurposeOrTamperedExtrasPacketIsRefused() {
        for (tamper in listOf(false, true)) {
            val key = PairingCrypto.secret()
            var asked = false
            var signedIn = false
            connect(
                { s -> PairingWire.serve(s, key, setOf("lastfm", "discord"), { signedIn = true; true }, { asked = true; it.kinds }) },
                { s ->
                    oldSender(key, s)
                    val packet = PairingCrypto.seal(key, both.encode(), if (tamper) "extras" else "session")
                    if (tamper) packet[20] = (packet[20].toInt() xor 1).toByte()
                    DataOutputStream(s.getOutputStream()).packet(packet)
                    runCatching { DataInputStream(s.getInputStream()).packet() }
                },
            )
            assertTrue(signedIn)
            assertFalse("tamper=$tamper", asked)
        }
    }

    @Test fun aSessionUnderAnotherKeyIsntAccepted() {
        val key = PairingCrypto.secret()
        var signedIn = false
        connect(
            { s -> runCatching { PairingWire.serve(s, key, setOf("lastfm"), { signedIn = true; true }, { it.kinds }) } },
            { s -> runCatching { oldSender(PairingCrypto.secret(), s) } },
        )
        assertFalse(signedIn)
    }
}
