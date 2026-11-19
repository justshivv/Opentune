package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two (or three) of a person's devices, each with a pretend player, talking
 * through an in-memory relay in real time, with every interval shortened.
 */
class ConnectSessionTest {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val group = ConnectGroup.generate()
    private val fast = Timings(presenceMs = 200, goneMs = 700, stateMs = 100, commandMs = 400, transferMs = 400, tickMs = 20)
    private val hub = Hub()

    @After fun tearDown() {
        scope.cancel()
        thread.close()
    }

    private inner class Hub {
        val flow = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024)
        /** Devices that stopped hearing anything, without saying goodbye. */
        val deaf = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        /**
         * How the relays treat each message: the delays its copies arrive after. Empty drops
         * it, two copies duplicate it, a longer delay than the next message's reorders them.
         */
        @Volatile var rule: (ConnectMsg) -> List<Long> = { listOf(0L) }
        fun deliver(event: NostrEvent): Boolean {
            val msg = group.open(event.content)?.let(ConnectMsg::decode) ?: return flow.tryEmit(event)
            for (d in rule(msg)) {
                if (d == 0L) flow.tryEmit(event)
                else kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(d); flow.emit(event) }
            }
            return true
        }
        /** Like real relays, nothing goes out until the transport has connected, [connectMs] after it starts. */
        fun transport(id: String, connectMs: Long = 0) = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024).also { mine ->
                kotlinx.coroutines.GlobalScope.launch { flow.collect { if (id !in deaf) mine.emit(it) } }
            }
            override val connected = MutableStateFlow(if (connectMs == 0L) 1 else 0)
            override fun start() {
                if (connectMs > 0) kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(connectMs); connected.value = 1 }
            }
            override fun publish(event: NostrEvent) = connected.value > 0 && deliver(event)
            override fun close() = Unit
        }
    }

    private fun track(id: Char) = CTrack(id.toString().repeat(11), "Song $id", "Artist")

    private class FakePlayer(var playable: Boolean = true) : ConnectPlayer {
        var track: CTrack? = null
        var queue: List<CTrack> = emptyList()
        var playing = false
        /** Meant to play, but stalled to load: not moving. */
        var stalled = false
        var pos = 0L
        var vol = 1f
        val calls = mutableListOf<String>()
        override fun snapshot() = track?.let { Snapshot(it, false, playing && !stalled, pos, queue, vol, false, "off", wants = playing) }
        override fun play() { calls += "play"; playing = true }
        override fun pause() { calls += "pause"; playing = false }
        override fun next() { calls += "next"; track = queue.firstOrNull(); queue = queue.drop(1); pos = 0 }
        override fun previous() { calls += "previous" }
        override fun seekTo(ms: Long) { calls += "seek $ms"; pos = ms }
        override fun setVolume(v: Float) { calls += "volume $v"; vol = v }
        override fun setShuffle(on: Boolean) { calls += "shuffle $on" }
        override fun setRepeat(mode: String) { calls += "repeat $mode" }
        override fun playTrack(t: CTrack) { calls += "playTrack ${t.id}"; track = t; playing = true }
        override fun addToQueue(ts: List<CTrack>) { calls += "add ${ts.size}"; queue = queue + ts }
        private fun at(at: Int, id: String): Int? = at.takeIf { queue.getOrNull(it)?.id == id } ?: queue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        override fun playQueued(at: Int, id: String): Boolean {
            val i = at(at, id) ?: return false
            calls += "jump $i"
            track = queue[i]; queue = queue.drop(i + 1)
            return true
        }
        override fun removeQueued(at: Int, id: String): Boolean {
            val i = at(at, id) ?: return false
            calls += "remove $i"
            queue = queue.filterIndexed { j, _ -> j != i }
            return true
        }
        override fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean {
            calls += "load ${queue.size} $index $pos $playing"
            if (!playable) return false
            track = queue[index]; this.queue = queue.drop(index + 1); this.pos = pos; this.playing = playing
            return true
        }
    }

    private inner class Dev(
        val id: String,
        val name: String,
        val player: FakePlayer = FakePlayer(),
        val timings: Timings = fast,
        val connectMs: Long = 0,
        val g: ConnectGroup = group,
        val former: List<ConnectGroup> = emptyList(),
        /** How far this device's clock is off the real time (and so the others'). */
        val skew: Long = 0,
    ) {
        val view = MutableStateFlow(ConnectView(id, name))
        val notices = mutableListOf<String>()
        val movedTo = mutableListOf<String>()
        lateinit var session: ConnectSession
        val clock = { System.currentTimeMillis() + skew }
        fun start(took: Long = 0): Dev {
            session = ConnectSession(g, id, name, "phone", player, hub.transport(id, connectMs), view, { t, n -> notices += t.replace("{0}", n) }, scope, timings, clock, moved = { movedTo += it }, former = former, took = took)
            onLoop { session.start() }
            return this
        }
        /** Connect starting over here (the app's screen made again, a setting changed), the player left as it is. */
        fun restart(): Dev {
            val took = onLoop { session.tookAt }
            onLoop { session.stop() }
            return start(took)
        }
        fun others() = view.value.devices.map { it.id }.toSet()
    }

    private fun <T> onLoop(f: () -> T): T = runBlocking { withContext(thread) { f() } }

    private fun eventually(what: String, ms: Long = 3_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (onLoop(check)) return
            Thread.sleep(10)
        }
        throw AssertionError("never: $what")
    }

    @Test fun devicesFindEachOtherAndForgetOnesThatGo() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("each sees the other") { a.others() == setOf("b") && b.others() == setOf("a") }
        assertEquals("Chrome", a.view.value.devices.single().name)
        onLoop { b.session.stop() }
        eventually("a forgets b") { a.others().isEmpty() }
    }

    @Test fun aDeviceSaysItIsHereOnceARelayConnects() {
        val slow = fast.copy(presenceMs = 60_000)
        val a = Dev("a", "Phone", timings = slow, connectMs = 50).start()
        val b = Dev("b", "Chrome", timings = slow, connectMs = 50).start()
        eventually("each sees the other", ms = 1_000) { a.others() == setOf("b") && b.others() == setOf("a") }
    }

    @Test fun aRestartedDeviceIsHeardAgain() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        // b has said a lot before it goes, more than a new b could catch up with by counting from 0.
        onLoop { ConnectSession::class.java.getDeclaredField("seq").apply { isAccessible = true }.setLong(b.session, 1_000_000_000L) }
        eventually("each sees the other") { a.others() == setOf("b") }
        Thread.sleep(300)
        onLoop { b.session.stop() }
        eventually("a forgets b") { a.others().isEmpty() }
        Thread.sleep(20)
        Dev("b", "Chrome").start()
        eventually("a sees the new b") { a.others() == setOf("b") }
    }

    @Test fun aDeviceJoiningAnotherGroupTakesTheOthersAlong() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("each sees the other") { a.others() == setOf("b") && b.others() == setOf("a") }
        onLoop { b.session.stop(next = "the-new-secret") }
        eventually("a follows b") { a.movedTo == listOf("the-new-secret") && a.others().isEmpty() }
        onLoop { a.session.stop() }
        Thread.sleep(200)
        assertEquals(emptyList<String>(), b.movedTo)
    }

    @Test fun theDevicePlayingIsTheOneShownElsewhere() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b shows a") { b.view.value.activeId == "a" && b.view.value.controlling?.state?.track == track('x') }
        assertEquals("a", a.view.value.activeId)
    }

    @Test fun aButtonPressedHereActsOnTheDevicePlaying() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b controls a") { b.view.value.controlling?.id == "a" }
        assertTrue(onLoop { b.session.intercept("pause") })
        assertFalse("shown paused at once", b.view.value.controlling!!.state!!.playing)
        eventually("a paused") { "pause" in a.player.calls }
        eventually("answered") { b.view.value.pending.isEmpty() }
        assertTrue(b.notices.isEmpty())
        assertTrue(onLoop { b.session.intercept("seek", pos = 42_000) })
        eventually("a seeked") { "seek 42000" in a.player.calls }
        // Nothing else playing: a's own buttons are its own.
        assertFalse(onLoop { a.session.intercept("pause") })
    }

    @Test fun aDeviceThatDoesntAnswerIsNamed() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b controls a") { b.view.value.controlling?.id == "a" }
        // a goes quiet without saying goodbye.
        hub.deaf += "a"
        Thread.sleep(50)
        onLoop { b.session.intercept("next") }
        eventually("notice") { b.notices.contains("Phone isn't answering") }
        assertTrue(b.view.value.pending.isEmpty())
    }

    @Test fun playbackMovesWithItsQueueAndPosition() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.queue = listOf(track('y')); a.player.playing = true; a.player.pos = 60_000; a.session.localChanged() }
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.session.transferTo("b") }
        eventually("b plays") { b.player.playing && b.player.track == track('x') }
        assertEquals(listOf(track('y')), b.player.queue)
        assertTrue(b.player.pos in 60_000..61_000)
        eventually("a stops") { !a.player.playing }
        eventually("both show b") { a.view.value.activeId == "b" && b.view.value.activeId == "b" }
    }

    @Test fun pausedMusicMovesAndStaysWhereItWasMoved() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.player.playing = false; a.session.localChanged() }
        Thread.sleep(50)
        onLoop { b.session.transferTo("b") }
        eventually("b has it") { b.player.track == track('x') && !b.player.playing }
        // a, told to pause (it already is), says so again: that doesn't take the music back.
        Thread.sleep(100)
        onLoop { a.session.localChanged() }
        Thread.sleep(200)
        eventually("both show b") { a.view.value.activeId == "b" && b.view.value.activeId == "b" }
    }

    @Test fun aReportFromBeforeAPauseDoesNotUndoItOnScreen() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b controls a") { b.view.value.controlling?.state?.playing == true }
        onLoop {
            b.session.intercept("pause")
            // a's report, sent before the pause reached it.
            b.session.handle(ConnectMsg.State("a", Long.MAX_VALUE - 1, System.currentTimeMillis(), track = track('x'), playing = true))
        }
        assertEquals(false, onLoop { b.view.value.controlling?.state?.playing })
    }

    @Test fun shuffleAndRepeatActOnTheDevicePlaying() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b controls a") { b.view.value.activeId == "a" }
        onLoop { b.session.intercept("repeat", mode = "all"); b.session.intercept("shuffle", value = 1f) }
        eventually("a set") { a.player.calls.containsAll(listOf("repeat all", "shuffle true")) }
    }

    @Test fun aDeviceThatMissedAMergeIsToldWhereTheOthersWent() {
        val merged = ConnectGroup.generate()
        // a moved from [group] to [merged] while c was off; c comes back still in [group].
        val a = Dev("a", "Phone", g = merged, former = listOf(group)).start()
        val c = Dev("c", "Laptop").start()
        eventually("c is told") { c.movedTo == listOf(merged.encoded) }
        // Someone in the new group isn't told anything.
        val b = Dev("b", "Chrome", g = merged).start()
        eventually("a and b meet") { a.others() == setOf("b") }
        assertEquals(emptyList<String>(), b.movedTo)
        assertEquals(setOf("b"), a.others())
    }

    @Test fun shuffleAndRepeatPressedHereArentUndoneByAReportFromBefore() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b controls a") { b.view.value.activeId == "a" }
        onLoop {
            b.session.intercept("shuffle", value = 1f)
            b.session.intercept("repeat", mode = "all")
            // a's report, sent before the presses reached it.
            b.session.handle(ConnectMsg.State("a", Long.MAX_VALUE - 1, System.currentTimeMillis(), track = track('x'), playing = true))
        }
        assertEquals(true, onLoop { b.view.value.controlling?.state?.shuffle })
        assertEquals("all", onLoop { b.view.value.controlling?.state?.repeat })
    }

    @Test fun upNextIsChangedFromAnotherDevice() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.queue = listOf(track('p'), track('q'), track('r')); a.player.playing = true; a.session.localChanged() }
        eventually("b sees a's queue") { b.view.value.controlling?.state?.queue?.size == 3 }
        onLoop { b.session.removeOn("a", 0, track('p')) }
        eventually("p gone") { a.player.queue.map { it.id.first() } == listOf('q', 'r') }
        // b still shows p first; r is entry 2 there, and is found by its id.
        onLoop { b.session.jumpOn("a", 2, track('r')) }
        eventually("r plays") { a.player.track?.id?.first() == 'r' }
    }

    @Test fun aControllerMovesPlaybackBetweenTwoOthersOrToItself() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        val c = Dev("c", "Laptop").start()
        eventually("all connected") { c.others() == setOf("a", "b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("c sees a") { c.view.value.activeId == "a" }
        onLoop { c.session.transferTo("b") }
        eventually("b plays, a stops") { b.player.playing && !a.player.playing }
        eventually("c sees b") { c.view.value.activeId == "b" }
        onLoop { c.session.transferTo("c") }
        eventually("c plays, b stops") { c.player.playing && !b.player.playing }
    }

    @Test fun aDeviceThatCantPlayItLeavesPlaybackWhereItWas() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome", FakePlayer(playable = false)).start()
        eventually("connected") { a.others() == setOf("b") }
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        onLoop { a.session.transferTo("b") }
        eventually("told") { a.notices.contains("Can't play this on Chrome") }
        assertTrue(a.player.playing)
    }

    @Test fun startingSomethingHereQuietsTheOtherDevice() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        onLoop { a.player.track = track('x'); a.player.playing = true; a.session.localChanged() }
        eventually("b sees a playing") { b.view.value.controlling?.state?.playing == true }
        onLoop { b.player.track = track('z'); b.player.playing = true; b.session.localChanged() }
        eventually("a paused") { !a.player.playing }
        eventually("both show b") { a.view.value.activeId == "b" && b.view.value.activeId == "b" }
    }

    @Test fun oldAndOwnMessagesAreIgnored() {
        val a = Dev("a", "Phone").start()
        onLoop {
            a.session.handle(ConnectMsg.Presence("b", 5, 0, "New", "web"))
            a.session.handle(ConnectMsg.Presence("b", 4, 0, "Old", "web"))
            a.session.handle(ConnectMsg.Presence("a", 99, 0, "Me", "web"))
        }
        assertEquals(listOf("New"), a.view.value.devices.map { it.name })
    }

    // ---- Handovers over relays that delay, reorder, duplicate and drop -------------------------

    private val x get() = track('x')
    private val y get() = track('y')

    private fun Dev.plays(t: CTrack, pos: Long = 0, queue: List<CTrack> = emptyList()) = onLoop {
        player.track = t; player.queue = queue; player.pos = pos; player.playing = true; session.localChanged()
    }

    private fun pair(a: Dev, b: Dev) = eventually("connected") { a.others() == setOf(b.id) && b.others() == setOf(a.id) }

    @Test fun anAnswerOvertakenByALaterReportStillLands() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x, 60_000)
        eventually("b sees a") { b.view.value.activeId == "a" }
        // b's answer to the handover takes a slow relay; what b says after it gets there first.
        hub.rule = { m -> if (m is ConnectMsg.Ack) listOf(150L) else listOf(0L) }
        onLoop { a.session.transferTo("b") }
        eventually("b plays") { b.player.playing }
        eventually("a stops") { !a.player.playing }
        Thread.sleep(fast.transferMs + 200)
        assertEquals(emptyList<String>(), a.notices)
    }

    @Test fun aRepeatedOrLateHandoverIsNotAppliedAgain() {
        val b = Dev("b", "Chrome").start()
        val now = System.currentTimeMillis()
        onLoop {
            val t = ConnectMsg.Transfer("a", 10, now, to = "b", id = "t1", queue = listOf(x), pos = 1_000)
            b.session.handle(t)
            b.session.handle(t) // a second relay's copy
            b.session.handle(ConnectMsg.Command("a", 12, now, to = "b", id = "c1", action = "pause"))
            // An older handover, held up on its way, arriving after the pause that followed it.
            b.session.handle(ConnectMsg.Transfer("a", 11, now, to = "b", id = "t0", queue = listOf(y), pos = 5_000))
        }
        assertEquals(1, b.player.calls.count { it.startsWith("load") })
        assertEquals(x, b.player.track)
        assertFalse(b.player.playing)
    }

    @Test fun aHandoverStartsWhereItWasWhateverTheClocksSay() {
        val a = Dev("a", "Phone").start()
        // The browser's clock runs 20 s ahead of the phone's.
        val b = Dev("b", "Chrome", skew = 20_000).start()
        pair(a, b)
        a.plays(x, 60_000)
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.session.transferTo("b") }
        eventually("b plays") { b.player.playing && b.player.track == x }
        assertTrue("started at ${b.player.pos}", b.player.pos in 60_000..61_000)
    }

    @Test fun theOtherDevicesClockIsLearnedOverTheRelays() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome", skew = 20_000).start()
        // Every message takes 100 ms to arrive.
        hub.rule = { listOf(100L) }
        pair(a, b)
        eventually("b knows a's clock") { b.session.clockOffset("a") != null }
        val offset = onLoop { b.session.clockOffset("a")!! }
        assertTrue("offset $offset", offset in -20_100L..-19_900L)
        a.plays(x, 60_000)
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.session.transferTo("b") }
        eventually("b plays") { b.player.playing && b.player.track == x }
        // Started on from where a was when it said so, the relay's 100 ms included.
        assertTrue("started at ${b.player.pos}", b.player.pos in 60_030..60_300)
    }

    @Test fun pausedMusicHandedOverKeepsItsPlace() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome", skew = 5_000).start()
        pair(a, b)
        a.plays(x, 30_000)
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.player.playing = false; a.session.localChanged() }
        Thread.sleep(50)
        onLoop { a.session.transferTo("b") }
        eventually("b has it") { b.player.track == x }
        assertEquals(30_000L, b.player.pos)
        assertFalse(b.player.playing)
    }

    @Test fun whoTookTheMusicLastIsJudgedOnEachDevicesOwnClock() {
        // The phone's clock runs a minute ahead.
        val a = Dev("a", "Phone", skew = 60_000).start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x)
        eventually("b sees a") { b.view.value.activeId == "a" }
        onLoop { a.player.playing = false; a.session.localChanged() }
        Thread.sleep(50)
        onLoop { b.session.transferTo("b") }
        eventually("b has it") { b.player.track == x }
        eventually("both show b") { a.view.value.activeId == "b" && b.view.value.activeId == "b" }
    }

    @Test fun aDeviceStalledToLoadIsStillTheOnePlaying() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x)
        eventually("b sees a") { b.view.value.activeId == "a" }
        Thread.sleep(30)
        // b has a song of its own loaded, paused; then a stalls to load.
        onLoop { b.player.track = y; b.session.localChanged() }
        onLoop { a.player.stalled = true; a.session.localChanged() }
        Thread.sleep(200)
        assertEquals("a", onLoop { a.view.value.activeId })
        assertEquals("a", onLoop { b.view.value.activeId })
        assertEquals(true, onLoop { b.view.value.controlling?.state?.meant })
        assertEquals(false, onLoop { b.view.value.controlling?.state?.playing })
    }

    @Test fun aPausedDeviceStartingConnectAgainDoesntTakeTheMusicBack() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        onLoop { a.player.track = x; a.session.localChanged() }
        Thread.sleep(30)
        b.plays(y)
        eventually("a shows b") { a.view.value.activeId == "b" }
        onLoop { b.player.playing = false; b.session.localChanged() }
        Thread.sleep(50)
        a.restart()
        Thread.sleep(300)
        pair(a, b)
        // Seeing b again comes before hearing what b plays: wait for that, then it has to stay with b.
        eventually("a hears b's state again") { a.view.value.activeId == "b" }
        Thread.sleep(300)
        assertEquals("b", onLoop { b.view.value.activeId })
        assertEquals("b", onLoop { a.view.value.activeId })
        assertFalse(onLoop { a.player.playing })
    }

    @Test fun bothDontKeepPlayingWhenTheAnswerAndThePauseAreLost() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x)
        eventually("b sees a") { b.view.value.activeId == "a" }
        // Nothing b sends to a in particular gets through: only what b says it plays.
        hub.rule = { m -> if (m.from == "b" && (m is ConnectMsg.Ack || m is ConnectMsg.Command)) emptyList() else listOf(0L) }
        onLoop { a.session.transferTo("b") }
        eventually("b plays") { b.player.playing }
        eventually("a stops") { !a.player.playing }
        eventually("both show b") { a.view.value.activeId == "b" && b.view.value.activeId == "b" }
        // b did take it: a doesn't say it couldn't.
        Thread.sleep(fast.transferMs + 100)
        assertFalse(a.notices.any { it.startsWith("Can't play") })
    }

    @Test fun aDuplicatingReorderingRelayLeavesOneDevicePlaying() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x, 60_000)
        eventually("b sees a") { b.view.value.activeId == "a" }
        // Every message twice, the second copy late; answers, commands and handovers slower than reports.
        hub.rule = { m -> if (m is ConnectMsg.Ack || m is ConnectMsg.Command || m is ConnectMsg.Transfer) listOf(60L, 250L) else listOf(0L, 200L) }
        onLoop { a.session.transferTo("b") }
        eventually("b plays, a stops") { b.player.playing && !a.player.playing }
        Thread.sleep(600)
        assertTrue(b.player.playing)
        assertFalse(a.player.playing)
        assertEquals(1, b.player.calls.count { it.startsWith("load") })
        assertFalse("play" in a.player.calls)
        assertEquals("b", onLoop { a.view.value.activeId })
        assertEquals("b", onLoop { b.view.value.activeId })
    }

    @Test fun aReportFromBeforeASeekDoesNotUndoIt() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x, 10_000)
        eventually("b controls a") { b.view.value.controlling?.id == "a" }
        onLoop {
            b.session.intercept("seek", pos = 42_000)
            // a's report, sent before the seek reached it.
            b.session.handle(ConnectMsg.State("a", Long.MAX_VALUE - 1, a.clock(), track = x, playing = true, pos = 10_000))
        }
        val shown = onLoop { b.view.value.controlling!!.positionAt(System.currentTimeMillis()) }
        assertTrue("shown at $shown", shown in 42_000..43_000)
        eventually("a seeked") { "seek 42000" in a.player.calls }
    }

    @Test fun theNextSongShowsFromItsStartAtOnce() {
        val a = Dev("a", "Phone").start()
        val b = Dev("b", "Chrome").start()
        pair(a, b)
        a.plays(x, 100_000, queue = listOf(y))
        eventually("b controls a") { b.view.value.controlling?.state?.track == x }
        onLoop { b.session.intercept("next") }
        fun shown() = onLoop { b.view.value.controlling!!.let { it.state!!.track to it.positionAt(System.currentTimeMillis()) } }
        assertEquals(y, shown().first)
        assertTrue("at ${shown().second}", shown().second < 1_000)
        // a's report from before the skip landed: neither the old song nor its place come back.
        onLoop { b.session.handle(ConnectMsg.State("a", Long.MAX_VALUE - 1, a.clock(), track = x, playing = true, pos = 100_000, queue = listOf(y))) }
        assertEquals(y, shown().first)
        assertTrue("at ${shown().second}", shown().second < 1_000)
        eventually("a skipped") { a.player.track == y }
    }
}
