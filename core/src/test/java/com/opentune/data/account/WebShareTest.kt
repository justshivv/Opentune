package com.opentune.data.account

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import com.opentune.data.together.Schnorr
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebShareTest {
    // Made with Node's own ECDH, HKDF and AES-GCM (not WebCrypto); web/app checks the same vector.
    private val extensionPublic = PairingCrypto.decode("BGv4XV_oRZixDKYZnsCczX013NcZX90dx3N6DGcAbvJRuVLyXuwd7tH8oZGDiZBGY1fTFK37fpdguNmvyPOala0")
    private val extensionPrivate = PairingCrypto.decode("Hx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8")
    private val phonePrivate = ByteArray(32) { 0x2e }
    private val phonePublic = PairingCrypto.decode("BAObhS22IkCKvlihjA8FZjGmykss_uwZiq4lAXytCdTo4gi2FuDcV3Wl2EB3XTja_UZ22jQQAhXovoV77SukrDA")
    private val packet = PairingCrypto.decode("AAECAwQFBgcICQoL9IFTbjdwCa2uB2cP0NdrBfDh8Fa786PIOf-cfDoGDfN8UpSFnOP49TxUCF2udYz91gmSprhG_CAOce1Ea4O4ncck83sdR0Przc1Gjg")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun thePhoneAgreesTheSameKeyAsTheExtension() {
        val key = ShareCrypto.sharedKey(ShareCrypto.privateKey(phonePrivate), extensionPublic, extensionPublic, phonePublic)
        assertEquals("043d0a13558e8f8cc2e2d388fa401d2b143b427b0b6bc13e304ebd837d87d1b4", hex(key))
        assertEquals("""{"discord":{"token":"mfa.TestTokenOnly_notReal-1234567890"}}""", String(ShareCrypto.open(key, packet, "extras")))
        assertTrue(runCatching { ShareCrypto.open(key, packet, "ack") }.isFailure)
        // The other way round, as the extension would.
        assertArrayEquals(key, ShareCrypto.sharedKey(ShareCrypto.privateKey(extensionPrivate), phonePublic, extensionPublic, phonePublic))
    }

    private fun payload(expires: Long, take: String = "discord", key: ByteArray = extensionPublic) =
        PairingCrypto.encode("w1|$expires|${PairingCrypto.encode(key)}|$take".toByteArray())

    @Test fun theWebCodeIsReadHereAndIgnoredByOlderPhones() {
        val now = 1_000_000L
        val offer = WebShareOffer.parse(payload(now + 60_000), now)
        assertEquals(setOf("discord"), offer.take)
        assertTrue(runCatching { PairingOffer.parse(payload(now + 60_000), now) }.isFailure)
        assertTrue(runCatching { WebShareOffer.parse(payload(now - 1), now) }.isFailure)
        assertTrue(runCatching { WebShareOffer.parse(payload(now + 60_000, take = ""), now) }.isFailure)
        val offCurve = extensionPublic.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertTrue(runCatching { WebShareOffer.parse(payload(now + 60_000, key = offCurve), now) }.isFailure)
    }

    /** Relays that hand every event to an extension stand-in, which answers as the real one does. */
    private class FakeRelays(relays: Int = 1, private val extension: (NostrEvent) -> NostrEvent?) : RoomTransport {
        private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<NostrEvent> = _events
        override val connected: StateFlow<Int> = MutableStateFlow(relays)
        val published = mutableListOf<NostrEvent>()
        var closed = false
        override fun start() {}
        override fun publish(event: NostrEvent): Boolean {
            published += event
            extension(event)?.let { _events.tryEmit(it) }
            return true
        }
        override fun close() { closed = true }
    }

    @Test fun onlyTheExtensionCanOpenWhatThePhoneSends() = runBlocking {
        val pair = ShareCrypto.keyPair()
        val extPub = ShareCrypto.raw(pair.public as ECPublicKey)
        val offer = WebShareOffer.parse(PairingCrypto.encode("w1|${System.currentTimeMillis() + 60_000}|${PairingCrypto.encode(extPub)}|discord".toByteArray()))
        var opened: PairingExtras? = null
        val relays = FakeRelays { event ->
            assertEquals(offer.tag, event.tags.single { it[0] == "t" }[1])
            val o = Json.parseToJsonElement(event.content).jsonObject
            val phonePub = PairingCrypto.decode((o["k"] as JsonPrimitive).content)
            val key = ShareCrypto.sharedKey(pair.private, phonePub, extPub, phonePub)
            opened = PairingExtras.decode(ShareCrypto.open(key, PairingCrypto.decode((o["s"] as JsonPrimitive).content), "extras"))
            val ack = ShareCrypto.seal(key, "discord".toByteArray(), "ack")
            NostrEvent.signed(Schnorr.newPrivateKey(), WebShare.KIND, event.tags, buildJsonObject { put("a", PairingCrypto.encode(ack)) }.toString())
        }
        val extras = PairingExtras(PairingExtras.Discord("mfa.NotARealToken_0123456789-abcdef"), PairingExtras.LastFm("abc", "wrenfield"))
        val took = WebShare.send(offer, extras) { _: JsonObject -> relays }
        assertEquals(setOf("discord"), took)
        assertEquals("mfa.NotARealToken_0123456789-abcdef", opened?.discord?.token)
        assertEquals(null, opened?.lastFm) // the web player didn't ask for Last.fm, so it never left the phone
        assertTrue(relays.closed)
        assertTrue(relays.published.none { it.content.contains("NotARealToken") })
    }

    private fun freshOffer(): WebShareOffer {
        val extPub = ShareCrypto.raw(ShareCrypto.keyPair().public as ECPublicKey)
        return WebShareOffer.parse(PairingCrypto.encode("w1|${System.currentTimeMillis() + 60_000}|${PairingCrypto.encode(extPub)}|discord".toByteArray()))
    }

    private val token = PairingExtras(PairingExtras.Discord("mfa.NotARealToken_0123456789-abcdef"))

    @Test fun sentButNeverAnsweredIsUnconfirmedNotACancel() = runBlocking {
        val relays = FakeRelays { null }
        val failure = runCatching { WebShare.send(freshOffer(), token, waitMs = 300) { relays } }.exceptionOrNull()
        // Not a CancellationException (what withTimeout throws), which the sheet passed on and never showed.
        assertTrue(failure.toString(), failure is WebShare.Unconfirmed)
        assertTrue(relays.published.isNotEmpty())
        assertTrue(relays.closed)
    }

    @Test fun noRelayReachedIsUnsent() = runBlocking {
        val relays = FakeRelays(relays = 0) { null }
        val failure = runCatching { WebShare.send(freshOffer(), token, waitMs = 300) { relays } }.exceptionOrNull()
        assertTrue(failure.toString(), failure is WebShare.Unsent)
        assertTrue(relays.published.isEmpty())
    }

    @Test fun anAnswerSealedUnderAnotherKeyIsNoAnswer() {
        val key = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }
        fun ack(k: ByteArray) = buildJsonObject { put("a", PairingCrypto.encode(ShareCrypto.seal(k, "discord".toByteArray(), "ack"))) }.toString()
        assertEquals(setOf("discord"), WebShare.took(key, ack(key)))
        assertEquals(null, WebShare.took(key, ack(other)))
        // The phone's own packet coming back is no answer either.
        assertEquals(null, WebShare.took(key, buildJsonObject { put("k", "x"); put("s", "y") }.toString()))
    }
}
