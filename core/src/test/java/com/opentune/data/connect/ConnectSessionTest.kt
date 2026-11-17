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

    private class Hub {
        val flow = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024)
        /** Devices that stopped hearing anything, without saying goodbye. */
        val deaf = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        /** Like real relays, nothing goes out until the transport has connected, [connectMs] after it starts. */
        fun transport(id: String, connectMs: Long = 0) = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 1024).also { mine ->
                kotlinx.coroutines.GlobalScope.launch { flow.collect { if (id !in deaf) mine.emit(it) } }
            }
            override val connected = MutableStateFlow(if (connectMs == 0L) 1 else 0)
            override fun start() {
                if (connectMs > 0) kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(connectMs); connected.value = 1 }
            }
            override fun publish(event: NostrEvent) = connected.value > 0 && flow.tryEmit(event)
            override fun close() = Unit
        }
    }

    private fun track(id: Char) = CTrack(id.toString().repeat(11), "Song $id", "Artist")

    private class FakePlayer(var playable: Boolean = true) : ConnectPlayer {
        var track: CTrack? = null
        var queue: List<CTrack> = emptyList()
        var playing = false
        var pos = 0L
        var vol = 1f
        val calls = mutableListOf<String>()
        override fun snapshot() = track?.let { Snapshot(it, false, playing, pos, queue, vol, false, "off") }
        override fun play() { calls += "play"; playing = true }
        override fun pause() { calls += "pause"; playing = false }
        override fun next() { calls += "next"; track = queue.firstOrNull(); queue = queue.drop(1) }
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
    ) {
        val view = MutableStateFlow(ConnectView(id, name))
        val notices = mutableListOf<String>()
        val movedTo = mutableListOf<String>()
        lateinit var session: ConnectSession
        fun start(): Dev {
            session = ConnectSession(g, id, name, "phone", player, hub.transport(id, connectMs), view, { t, n -> notices += t.replace("{0}", n) }, scope, timings, moved = { movedTo += it }, former = former)
            onLoop { session.start() }
            return this
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
}
