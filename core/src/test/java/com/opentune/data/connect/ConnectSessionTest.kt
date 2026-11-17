package com.opentune.data.connect

import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
        var silent = mutableSetOf<String>()
        fun transport() = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = flow
            override val connected: StateFlow<Int> = MutableStateFlow(1)
            override fun start() = Unit
            override fun publish(event: NostrEvent) = flow.tryEmit(event)
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
        override fun playTrack(t: CTrack) { calls += "playTrack ${t.id}"; track = t; playing = true }
        override fun addToQueue(ts: List<CTrack>) { calls += "add ${ts.size}"; queue = queue + ts }
        override fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean {
            calls += "load ${queue.size} $index $pos $playing"
            if (!playable) return false
            track = queue[index]; this.queue = queue.drop(index + 1); this.pos = pos; this.playing = playing
            return true
        }
    }

    private inner class Dev(val id: String, val name: String, val player: FakePlayer = FakePlayer()) {
        val view = MutableStateFlow(ConnectView(id, name))
        val notices = mutableListOf<String>()
        lateinit var session: ConnectSession
        fun start(): Dev {
            session = ConnectSession(group, id, name, "phone", player, hub.transport(), view, { t, n -> notices += t.replace("{0}", n) }, scope, fast)
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
        onLoop { a.session.stop() }
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
