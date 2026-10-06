package com.opentune.data.together

import com.opentune.data.model.Song
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host and a guest session run against each other through an in-memory
 * relay, each with a pretend player, in real time. This is the room's
 * behaviour end to end, minus the network and ExoPlayer.
 */
class RoomSessionTest {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    @After fun tearDown() {
        scope.cancel()
        thread.close()
    }

    private fun song(id: String, title: String) = Song(id, title, "Artist", null, "3:30")
    private val a = song("aaaaaaaaaaa", "Song A")
    private val b = song("bbbbbbbbbbb", "Song B")
    private val c = song("ccccccccccc", "Song C")

    /** Everyone hears everything, the way a relay subscription on one tag does. */
    private class Hub {
        val flow = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        fun transport() = object : RoomTransport {
            override val events: SharedFlow<NostrEvent> = flow
            override val connected: StateFlow<Int> = MutableStateFlow(1)
            override fun start() = Unit
            override fun publish(event: NostrEvent) = flow.tryEmit(event)
            override fun close() = Unit
        }
    }

    /** A player whose position runs on the wall clock while it plays. */
    private class FakePlayer : RoomPlayer {
        private val _queue = MutableStateFlow<List<Song>>(emptyList())
        private val _index = MutableStateFlow(0)
        private val _song = MutableStateFlow<Song?>(null)
        private val _upNext = MutableStateFlow<List<Int>>(emptyList())
        private val _pwr = MutableStateFlow(false)
        override val connected = MutableStateFlow(true).asStateFlow()
        override val currentSong = _song.asStateFlow()
        override val queue = _queue.asStateFlow()
        override val upNext = _upNext.asStateFlow()
        override val currentIndex = _index.asStateFlow()
        override val playWhenReady = _pwr.asStateFlow()
        override val isBuffering = MutableStateFlow(false).asStateFlow()
        private var base = 0L
        private var baseAt = 0L
        var skips = 0

        override fun currentPositionMs() = if (_pwr.value) base + (System.currentTimeMillis() - baseAt) else base
        private fun rebase(pos: Long) { base = pos; baseAt = System.currentTimeMillis() }
        private fun refresh() {
            _song.value = _queue.value.getOrNull(_index.value)
            _upNext.value = ((_index.value + 1) until _queue.value.size).toList()
        }
        override fun play() { rebase(currentPositionMs()); _pwr.value = true }
        override fun pause() { rebase(currentPositionMs()); _pwr.value = false }
        override fun seekTo(positionMs: Long) = rebase(positionMs)
        override fun seekToItem(index: Int, positionMs: Long) { _index.value = index; rebase(positionMs); refresh() }
        override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) {
            _queue.value = songs; _index.value = startIndex; rebase(startPositionMs); _pwr.value = true; refresh()
        }
        override fun replaceUpcoming(songs: List<Song>) { _queue.value = _queue.value.take(_index.value + 1) + songs; refresh() }
        override fun addToQueue(song: Song) { _queue.value = _queue.value + song; refresh() }
        override fun skipNext() { skips++; if (_index.value + 1 < _queue.value.size) seekToItem(_index.value + 1, 0) }
        override fun skipPrevious() = seekTo(0)
    }

    private suspend fun <T> onThread(f: () -> T): T = withContext(thread) { f() }

    private suspend fun waitFor(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (onThread(check)) return
            delay(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test fun aGuestFollowsTheHost() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val guestPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        val notices = mutableListOf<String>()
        lateinit var host: RoomSession
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a, b), 0, false, null, 30_000)
            host = RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope).also { it.start() }
            guest = RoomSession(code, "Ravi", false, guestPlayer, hub.transport(), guestState, { notices += it }, scope).also { it.start() }
        }

        // Joining: the guest plays the host's song from where the host is.
        waitFor("guest playing song A in step") {
            guestPlayer.currentSong.value?.videoId == a.videoId && guestPlayer.playWhenReady.value &&
                abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS
        }
        waitFor("both see two people") { hostState.value!!.members.size == 2 && guestState.value!!.members.size == 2 }
        assertEquals(Together.Phase.Live, guestState.value!!.phase)
        assertEquals("Asha", guestState.value!!.hostName)
        assertEquals(listOf(b.videoId), onThread { guestPlayer.queue.value.drop(1).map { it.videoId } })

        // Pause, then seek and play again.
        onThread { hostPlayer.pause() }
        waitFor("guest paused") { !guestPlayer.playWhenReady.value }
        onThread { hostPlayer.seekTo(100_000); hostPlayer.play() }
        waitFor("guest at the new spot") {
            guestPlayer.playWhenReady.value && abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS &&
                guestPlayer.currentPositionMs() > 99_000
        }

        // Next song.
        onThread { hostPlayer.skipNext() }
        waitFor("guest on song B") { guestPlayer.currentSong.value?.videoId == b.videoId }

        // A guest's pick goes into the host's queue, and chat goes both ways.
        onThread { assertTrue(guest.suggest(c)) }
        waitFor("song C queued on the host") { hostPlayer.queue.value.any { it.videoId == c.videoId } }
        waitFor("song C after B on the guest too") { guestPlayer.queue.value.any { it.videoId == c.videoId } }
        onThread { guest.say("hi from Ravi") }
        waitFor("host sees the message") { hostState.value!!.chat.any { it.name == "Ravi" && it.text == "hi from Ravi" } }
        onThread { host.say("hello Ravi") }
        waitFor("guest sees the reply") { guestState.value!!.chat.any { it.name == "Asha" && it.text == "hello Ravi" } }

        // Without control, a guest can't skip for everyone, and pausing is just for them.
        onThread { assertTrue(guest.intercept(Together.Control.Next)) }
        delay(800)
        assertEquals(1, onThread { hostPlayer.skips })
        assertTrue(notices.any { it.startsWith("Only Asha") })
        onThread { guest.intercept(Together.Control.PlayPause) }
        waitFor("guest holding") { guestState.value!!.holding && !guestPlayer.playWhenReady.value }
        delay(1_500)
        assertFalse("the room shouldn't restart a held guest", onThread { guestPlayer.playWhenReady.value })
        assertTrue(onThread { hostPlayer.playWhenReady.value })
        onThread { guest.intercept(Together.Control.PlayPause) }
        waitFor("guest caught up") { guestPlayer.playWhenReady.value && abs(guestPlayer.currentPositionMs() - hostPlayer.currentPositionMs()) < DRIFT_MS }

        // With control, a guest's skip happens for everyone.
        onThread { host.setOpen(true) }
        waitFor("guest knows it may control") { guestState.value!!.open }
        onThread { guest.intercept(Together.Control.Next) }
        waitFor("host skipped") { hostPlayer.currentSong.value?.videoId == c.videoId }
        waitFor("guest followed") { guestPlayer.currentSong.value?.videoId == c.videoId }

        // Ending the room tells the guest.
        onThread { host.stop(saying = true) }
        waitFor("guest sees the end") { guestState.value!!.phase == Together.Phase.Ended }
        assertTrue(guestState.value!!.endedReason!!.contains("Asha"))
    }

    @Test fun aGuestLeavingIsNoticed() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        val hostPlayer = FakePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        lateinit var guest: RoomSession
        onThread {
            hostPlayer.playAll(listOf(a), 0, false, null, 0)
            RoomSession(code, "Asha", true, hostPlayer, hub.transport(), hostState, {}, scope).start()
            guest = RoomSession(code, "Ravi", false, FakePlayer(), hub.transport(), guestState, {}, scope).also { it.start() }
        }
        waitFor("joined") { hostState.value!!.members.size == 2 }
        onThread { guest.stop(saying = true) }
        waitFor("left") { hostState.value!!.members.size == 1 && hostState.value!!.chat.any { it.text == "Ravi left" } }
    }

    @Test fun anotherRoomsMessagesAreIgnored() = runBlocking {
        val hub = Hub()
        val mine = RoomCode.generate()
        val other = RoomCode.generate()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(mine, true, "Asha"))
        val strangerState = MutableStateFlow<Together.Room?>(Together.Room(other, false, "Mallory"))
        onThread {
            RoomSession(mine, "Asha", true, FakePlayer(), hub.transport(), hostState, {}, scope).start()
            RoomSession(other, "Mallory", false, FakePlayer(), hub.transport(), strangerState, {}, scope).start()
        }
        delay(3_000)
        assertEquals(1, hostState.value!!.members.size)
        assertEquals(Together.Phase.FindingHost, strangerState.value!!.phase)
    }
}
