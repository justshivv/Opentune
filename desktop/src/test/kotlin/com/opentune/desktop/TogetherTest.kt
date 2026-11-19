package com.opentune.desktop

import com.opentune.data.model.Song
import com.opentune.data.together.Member
import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomCode
import com.opentune.data.together.RoomPlayer
import com.opentune.data.together.RoomSession
import com.opentune.data.together.RoomTransport
import com.opentune.data.together.Together
import java.util.concurrent.Executors
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone and this computer in one Listen together room. The desktop hosts with its own player
 * (DesktopRoomPlayer, over the real Player); the phone is the same room code (core's RoomSession,
 * compiled here as it is) over a pretend player, as core's RoomSessionTest drives it. They talk
 * through an in-memory relay, in real time: knocking and being let in, following the host's song
 * and queue, a guest's pick landing in the desktop's queue, chat both ways, and being put out.
 */
class TogetherTest {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    @After fun tearDown() {
        scope.cancel()
        thread.close()
    }

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

    /** The phone's player, as far as a room sees it. */
    private class PhonePlayer : RoomPlayer {
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
        override val seeks: SharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 8)
        private var pos = 0L
        override fun currentPositionMs() = pos
        override fun setRate(rate: Float) = Unit
        private fun refresh() {
            _song.value = _queue.value.getOrNull(_index.value)
            _upNext.value = ((_index.value + 1) until _queue.value.size).toList()
        }
        override fun play() { _pwr.value = true }
        override fun pause() { _pwr.value = false }
        override fun seekTo(positionMs: Long) { pos = positionMs }
        override fun seekToItem(index: Int, positionMs: Long) { _index.value = index; pos = positionMs; refresh() }
        override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) {
            _queue.value = songs; _index.value = startIndex; pos = startPositionMs; refresh()
        }
        override fun replaceUpcoming(songs: List<Song>) { _queue.value = _queue.value.take(_index.value + 1) + songs; refresh() }
        override fun addToQueue(song: Song) { _queue.value = _queue.value + song; refresh() }
        override fun playNow(song: Song) = addToQueue(song)
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
    }

    private suspend fun <T> onThread(f: () -> T): T = withContext(thread) { f() }

    private suspend fun waitFor(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (onThread(check)) return
            delay(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun song(n: Int) = Song("t${n.toString().padStart(10, '0')}", "Song $n", "Artist", null, "3:30")

    @Test fun aPhoneJoinsARoomThisComputerHosts() = runBlocking {
        val hub = Hub()
        val code = RoomCode.generate()
        Player.cue((0 until 4).map(::song), 1)
        delay(300)
        val phone = PhonePlayer()
        val hostState = MutableStateFlow<Together.Room?>(Together.Room(code, true, "Asha"))
        val guestState = MutableStateFlow<Together.Room?>(Together.Room(code, false, "Ravi"))
        lateinit var host: RoomSession
        lateinit var guest: RoomSession
        onThread {
            host = RoomSession(code, "Asha", true, DesktopRoomPlayer, hub.transport(), hostState, {}, scope, askToJoin = true).also { it.start() }
            guest = RoomSession(code, "Ravi", false, phone, hub.transport(), guestState, {}, scope).also { it.start() }
        }

        // The phone knocks; the desktop lets it in.
        waitFor("the desktop sees Ravi knock") { hostState.value!!.requests.any { it.name == "Ravi" } }
        waitFor("the phone told to wait") { guestState.value!!.phase == Together.Phase.Waiting }
        val ravi: Member = hostState.value!!.requests.first()
        onThread { host.approve(ravi.id) }
        waitFor("the phone is in") { guestState.value!!.phase == Together.Phase.Live }
        assertEquals("Asha", guestState.value!!.hostName)

        // The phone plays the desktop's song, with the desktop's queue after it.
        waitFor("the phone on the desktop's song") { phone.currentSong.value?.title == "Song 1" }
        waitFor("the phone has the desktop's queue") { phone.queue.value.drop(phone.currentIndex.value + 1).map { it.title } == listOf("Song 2", "Song 3") }
        waitFor("both see two people") { hostState.value!!.members.size == 2 && guestState.value!!.members.size == 2 }

        // A song picked on the phone lands in the desktop's queue.
        onThread { assertTrue(guest.suggest(song(9))) }
        waitFor("the pick in the desktop's queue") { Player.queue.value.any { it.title == "Song 9" } }

        // Chat goes both ways.
        onThread { guest.say("hi from the phone") }
        waitFor("the desktop reads it") { hostState.value!!.chat.any { it.name == "Ravi" && it.text == "hi from the phone" } }
        onThread { host.say("hello from the desktop") }
        waitFor("the phone reads it") { guestState.value!!.chat.any { it.name == "Asha" && it.text == "hello from the desktop" } }

        // The host's switch reaches the phone.
        onThread { host.setOpen(true) }
        waitFor("the phone may control") { guestState.value!!.open }

        // Put out, the phone's room ends.
        onThread { host.remove(ravi.id) }
        waitFor("the phone is out") { guestState.value!!.phase == Together.Phase.Ended }
        waitFor("the desktop is alone again") { hostState.value!!.members.size == 1 }
        onThread { host.stop(saying = true) }
    }
}
