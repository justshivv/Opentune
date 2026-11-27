package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch and the phone pairing through an in-memory relay. */
class WatchPairingTest {
    private class Hub {
        val flow = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        fun transport() = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256).also { mine ->
                GlobalScope.launch { flow.collect { mine.emit(it) } }
            }
            override val connected = MutableStateFlow(1)
            override fun start() = Unit
            override fun publish(event: NostrEvent) = flow.tryEmit(event)
            override fun close() = Unit
        }
    }

    private val group = ConnectGroup.generate()

    @Test fun theWatchGetsTheGroupAndThePhoneHearsBack() = runBlocking {
        val hub = Hub()
        val key = WatchPairingKey.generate()
        var listening = false
        val watch = async { WatchPairing.receive(key, "Pixel Watch", { listening = true }, hub.transport(), 5_000) }
        val name = WatchPairing.send(key, WatchOffer(group.encoded, "SAPISID=x", "Pixel 9"), hub.transport(), 5_000)
        val offer = watch.await()
        assertTrue(listening)
        assertEquals("Pixel Watch", name)
        assertEquals(group.encoded, offer?.group)
        assertEquals("SAPISID=x", offer?.cookie)
        assertEquals("Pixel 9", offer?.name)
    }

    @Test fun anotherKeyHearsNothing() = runBlocking {
        val hub = Hub()
        val key = WatchPairingKey.generate()
        val other = WatchPairingKey.generate()
        val watch = async { WatchPairing.receive(key, "Watch", {}, hub.transport(), 2_500) }
        // Sent with the wrong key: the watch can't open it, and nobody answers.
        assertNull(WatchPairing.send(other, WatchOffer(group.encoded, null, "Phone"), hub.transport(), 2_000))
        assertNull(watch.await())
    }

    @Test fun noAccountMeansNoCookie() = runBlocking {
        val hub = Hub()
        val key = WatchPairingKey.generate()
        val watch = async { WatchPairing.receive(key, "Watch", {}, hub.transport(), 5_000) }
        WatchPairing.send(key, WatchOffer(group.encoded, null, "Phone"), hub.transport(), 5_000)
        assertNull(watch.await()?.cookie)
    }

    @Test fun theLinkCarriesTheKeyAndNothingElseParses() {
        val key = WatchPairingKey.generate()
        assertTrue(key.link.startsWith("opentune://watch/#"))
        assertEquals(key.encoded, WatchPairingKey.parse(key.link)?.encoded)
        assertNull(WatchPairingKey.parse(group.appLink))
        assertNull(WatchPairingKey.parse("opentune://watch/#short"))
        assertNull(WatchPairingKey.parse(null))
    }

    @Test fun itsTagIsNeverTheGroupsForTheSameBytes() {
        val key = WatchPairingKey(group.secret.copyOf())
        assertNotEquals(group.tag, key.tag)
    }

    @Test fun anOfferWithoutAGroupIsRefused() {
        assertNull(WatchPairing.parseOffer("""{"t":"offer","v":1,"group":"nope","name":"x"}"""))
        assertNull(WatchPairing.parseOffer("""{"t":"ack","name":"x"}"""))
        assertEquals(group.encoded, WatchPairing.parseOffer(WatchPairing.offerText(WatchOffer(group.encoded, null, "P")))?.group)
    }
}
