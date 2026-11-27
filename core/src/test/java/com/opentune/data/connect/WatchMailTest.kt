package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchMailTest {
    private val secret = ByteArray(32) { it.toByte() }
    private fun request(id: String, at: Long = 1_000) = WatchRequest(id, WatchRequest.SONG, "v$id", "Song $id", at = at)

    @Test
    fun everyDeviceDerivesTheSameBoxesAndWatchesGetTheirOwn() {
        val a = WatchMail(secret)
        val b = WatchMail(secret.copyOf())
        assertEquals(a.author, b.author)
        assertEquals(a.wantedTag("w1", "p"), b.wantedTag("w1", "p"))
        assertNotEquals(a.wantedTag("w1", "p"), a.wantedTag("w2", "p"))
        assertNotEquals(a.takenTag("w1"), a.takenTag("w2"))
        assertNotEquals(a.author, WatchMail(ByteArray(32) { 7 }).author)
    }

    @Test
    fun aBoxOpensOnlyForItsGroupAndUnderItsOwnTag() {
        val mail = WatchMail(secret)
        val box = WantedBox("w1", "p", listOf(request("1").copy(durationText = "3:45")))
        val event = mail.wantedEvent(box, 100)
        assertEquals(box, mail.open(event)?.wanted)
        assertEquals("3:45", mail.open(event)?.wanted?.requests?.single()?.durationText)
        assertNull(WatchMail(ByteArray(32) { 9 }).open(event))
        // The same sealed box filed under another watch's tag doesn't open.
        val moved = event.copy(tags = listOf(listOf("d", mail.wantedTag("w2", "p"))))
        assertNull(mail.open(moved))
        val taken = mail.takenEvent(TakenBox("w1", listOf("1")), 101)
        assertEquals(listOf("1"), mail.open(taken)?.taken?.taken)
    }

    @Test
    fun theWatchTakesOnlyWhatsNewForItAndForgetsWhatsBeenDropped() {
        val now = 10_000L
        val boxes = listOf(
            WantedBox("w1", "p", listOf(request("2", at = 2), request("1", at = 1))),
            WantedBox("w2", "p", listOf(request("3"))),
        )
        assertEquals(listOf("1", "2"), WatchMailRules.toTake("w1", boxes, emptySet(), now).map { it.id })
        assertEquals(listOf("2"), WatchMailRules.toTake("w1", boxes, setOf("1"), now).map { it.id })
        assertEquals(setOf("1"), WatchMailRules.keepTaken("w1", boxes, setOf("1", "gone")))
        val old = WantedBox("w1", "p", listOf(request("4", at = 0)))
        assertTrue(WatchMailRules.toTake("w1", listOf(old), emptySet(), WatchMail.KEEP_MS + 1).isEmpty())
    }

    @Test
    fun theSenderDropsWhatTheWatchTookAndWhatsTooOld() {
        val box = WantedBox("w1", "p", listOf(request("1", at = 5), request("2", at = 5), request("3", at = 0)))
        val pruned = WatchMailRules.prune(box, TakenBox("w1", listOf("1")), WatchMail.KEEP_MS + 1)
        assertEquals(listOf("2"), pruned.requests.map { it.id })
    }

    private class Store : MailStore {
        override var wanted: Map<String, WantedBox> = emptyMap()
        override var handled: Map<String, Long> = emptyMap()
    }

    /** Relays that keep each box's newest version, as real ones keep addressable events. */
    private class Relay {
        val kept = mutableMapOf<String, NostrEvent>()
        val live = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        fun transport() = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = live
            override val connected: StateFlow<Int> = MutableStateFlow(1)
            override fun start() {}
            override fun publish(event: NostrEvent): Boolean {
                val d = event.tags.first { it[0] == "d" }[1]
                synchronized(kept) { kept[d] = event }
                return live.tryEmit(event)
            }
            override fun close() {}
        }
        suspend fun replay() = synchronized(kept) { kept.values.toList() }.forEach { live.emit(it) }
    }

    @Test
    fun aRequestWaitsForTheWatchAndIsDroppedOnceTaken() = runBlocking {
        val mail = WatchMail(secret)
        val relay = Relay()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val phoneStore = Store()
            val phone = WatchMailbox(mail, "p", isWatch = false, relay.transport(), phoneStore, scope, null)
            phone.start()
            delay(100)
            phone.send("w1", request("1", at = System.currentTimeMillis()))
            withTimeout(2_000) { while (phone.waiting.value["w1"] != 1) delay(10) }

            // The watch comes online later and hears the stored boxes.
            val took = mutableListOf<String>()
            val watchStore = Store()
            val watch = WatchMailbox(mail, "w1", isWatch = true, relay.transport(), watchStore, scope, { rs -> took += rs.map { it.id }; rs.map { it.id }.toSet() })
            watch.start()
            delay(100)
            relay.replay()
            withTimeout(2_000) { while (phone.waiting.value["w1"] != null) delay(10) }
            assertEquals(listOf("1"), took)
            assertNotNull(watchStore.handled["1"])
            assertTrue(phoneStore.wanted.isEmpty())

            // Heard again (another relay, a restart): not downloaded twice.
            relay.replay()
            delay(200)
            assertEquals(listOf("1"), took)
        } finally {
            scope.cancel()
        }
    }
}
