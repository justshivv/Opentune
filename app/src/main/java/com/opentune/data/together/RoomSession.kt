package com.opentune.data.together

import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import com.opentune.data.radio.Radio
import com.opentune.data.together.Together.ChatLine
import com.opentune.data.together.Together.Control
import com.opentune.data.together.Together.Phase
import com.opentune.data.together.Together.Room
import com.opentune.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a room needs from the player. */
internal interface RoomPlayer {
    val connected: StateFlow<Boolean>
    val currentSong: StateFlow<Song?>
    val queue: StateFlow<List<Song>>
    val upNext: StateFlow<List<Int>>
    val currentIndex: StateFlow<Int>
    val playWhenReady: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    fun currentPositionMs(): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekToItem(index: Int, positionMs: Long)
    fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long)
    fun replaceUpcoming(songs: List<Song>)
    fun addToQueue(song: Song)
    fun skipNext()
    fun skipPrevious()
}

/** The app's player, as a room sees it. */
internal class ConnectionPlayer(private val c: PlayerConnection) : RoomPlayer {
    override val connected get() = c.connected
    override val currentSong get() = c.currentSong
    override val queue get() = c.queue
    override val upNext get() = c.upNext
    override val currentIndex get() = c.currentIndex
    override val playWhenReady get() = c.playWhenReady
    override val isBuffering get() = c.isBuffering
    override fun currentPositionMs() = c.currentPositionMs()
    override fun play() = c.play()
    override fun pause() = c.pause()
    override fun seekTo(positionMs: Long) = c.seekTo(positionMs)
    override fun seekToItem(index: Int, positionMs: Long) = c.seekToItem(index, positionMs)
    override fun playAll(songs: List<Song>, startIndex: Int, shuffle: Boolean, source: String?, startPositionMs: Long) =
        c.playAll(songs, startIndex, shuffle, source, startPositionMs)
    override fun replaceUpcoming(songs: List<Song>) = c.replaceUpcoming(songs)
    override fun addToQueue(song: Song) = c.addToQueue(song)
    override fun skipNext() = c.skipNext()
    override fun skipPrevious() = c.skipPrevious()
}

/** Where a room's events go and come from; the relays in the app. */
internal interface RoomTransport {
    val events: SharedFlow<NostrEvent>
    val connected: StateFlow<Int>
    fun start()
    fun publish(event: NostrEvent): Boolean
    fun close()
}

private const val HEARTBEAT_MS = 4_000L
private const val HERE_MS = 20_000L
private const val MEMBER_TIMEOUT_MS = 50_000L
private const val HOST_TIMEOUT_MS = 25_000L
private const val MAX_CHAT = 100
private const val TAG = "Together"
/** How far a guest may drift before it seeks back into step. */
internal const val DRIFT_MS = 1_200L

private class Member0(val name: String, var lastSeen: Long)

/**
 * One phone's part in one room. The player, the relays, where the room's
 * state is published and the scope it runs in all come from outside, so
 * a host and a guest can be run against each other in a test.
 */
internal class RoomSession(
    val code: RoomCode,
    val myName: String,
    val hosting: Boolean,
    private val player: RoomPlayer,
    private val pool: RoomTransport,
    private val state: MutableStateFlow<Room?>,
    private val notice: (String) -> Unit,
    private val main: CoroutineScope,
) {
    private val key = Schnorr.newPrivateKey()
    private val me = Schnorr.publicKey(key).toHex()
    private val outbox = Channel<Msg>(Channel.BUFFERED)
    private val jobs = mutableListOf<Job>()
    private val members = linkedMapOf<String, Member0>()

    // host
    private var seq = 0L
    private var open = false
    private var lastSent: Msg.State? = null
    private var lastSentLocal = 0L

    // guest
    private var hostKey: String? = null
    private var last: Msg.State? = null
    private var lastAt = 0L
    private var offset = 0L
    private var bestRtt = Long.MAX_VALUE
    private var settleUntil = 0L
    private var holding = false

    fun start() {
        pool.start()
        jobs += main.launch { pool.connected.collect { n -> update { it.copy(relays = n, phase = if (it.phase == Phase.Connecting && n > 0) (if (hosting) Phase.Live else Phase.FindingHost) else it.phase) } } }
        jobs += main.launch { pool.events.collect(::receive) }
        // Signing is BigInteger work; keep it off the main thread, in order.
        // Not in [jobs]: it has to outlive stop() long enough to send the goodbye.
        CoroutineScope(Dispatchers.Default).launch {
            for (m in outbox) {
                val content = code.seal(Msg.encode(m))
                val event = NostrEvent.signed(key, Together.KIND, listOf(listOf("t", code.tag)), content)
                if (!pool.publish(event)) Log.w(TAG, "no relay connected for ${m::class.simpleName}")
            }
        }
        if (hosting) startHost() else startGuest()
    }

    fun stop(saying: Boolean) {
        if (saying) send(if (hosting) Msg.End else Msg.Bye)
        main.launch {
            delay(600) // let the goodbye go out
            outbox.close()
            pool.close()
        }
        jobs.forEach { it.cancel() }
    }

    private fun send(m: Msg) {
        outbox.trySend(m)
    }

    private fun update(f: (Room) -> Room) {
        state.update { r -> if (r == null || r.code != code) r else f(r) }
    }

    private fun addChat(line: ChatLine) = update { it.copy(chat = (it.chat + line).takeLast(MAX_CHAT)) }

    private fun receive(event: NostrEvent) {
        if (event.pubkey == me) return
        val msg = code.open(event.content)?.let(Msg::decode) ?: return
        if (hosting) hostHandle(event.pubkey, msg) else guestHandle(event.pubkey, msg)
    }

    // ---------------- host ----------------

    private fun startHost() {
        update { it.copy(hostName = myName, members = memberList(), phase = if (pool.connected.value > 0) Phase.Live else Phase.Connecting) }
        addChat(ChatLine("", "Room open. Share the code so friends can join.", system = true))
        val p = player
        jobs += main.launch {
            combine(
                p.currentSong.map { it?.videoId }.distinctUntilChanged(),
                p.playWhenReady,
                p.isBuffering,
                p.queue.map { q -> q.map { it.videoId } },
                p.currentIndex,
            ) { a, b, c, d, e -> listOf(a, b, c, d, e) }.collectLatest {
                delay(150) // one message for a burst of changes
                sendState()
            }
        }
        jobs += main.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val sent = lastSent
                val pos = p.currentPositionMs()
                // A seek shows up as the position jumping from where it should be.
                val jumped = sent != null && sent.track != null &&
                    kotlin.math.abs(pos - targetPosition(sent, now, 0)) > 2_000
                if (jumped || now - lastSentLocal > HEARTBEAT_MS) sendState()
                val gone = members.filterValues { now - it.lastSeen > MEMBER_TIMEOUT_MS }
                if (gone.isNotEmpty()) {
                    gone.keys.forEach(members::remove)
                    gone.values.forEach { addChat(ChatLine("", "${it.name} left", system = true)) }
                    update { it.copy(members = memberList()) }
                }
            }
        }
    }

    private fun memberList() = listOf(Member(me, myName, host = true)) + members.map { (id, m) -> Member(id, m.name) }

    private fun sendState() {
        val p = player
        val song = p.currentSong.value
        val track = song?.let(Track::of)
        val upcoming = p.upNext.value.take(10).mapNotNull { i -> p.queue.value.getOrNull(i)?.let(Track::of) }
        val s = Msg.State(
            seq = ++seq,
            host = myName,
            track = track,
            private = song != null && track == null,
            playing = p.playWhenReady.value && !p.isBuffering.value,
            pos = p.currentPositionMs(),
            at = System.currentTimeMillis(),
            next = upcoming,
            members = memberList(),
            open = open,
        )
        lastSent = s
        lastSentLocal = System.currentTimeMillis()
        update { it.copy(track = track, hostPrivate = s.private, open = open, members = s.members) }
        send(s)
    }

    private fun hostHandle(from: String, msg: Msg) {
        val now = System.currentTimeMillis()
        val p = player
        when (msg) {
            is Msg.Hello, is Msg.Here -> {
                val name = ((msg as? Msg.Hello)?.name ?: (msg as Msg.Here).name).take(30)
                val known = members[from]
                if (known == null) {
                    members[from] = Member0(name, now)
                    addChat(ChatLine("", "$name joined", system = true))
                    update { it.copy(members = memberList()) }
                } else known.lastSeen = now
                if (msg is Msg.Hello) sendState()
            }
            Msg.Bye -> members.remove(from)?.let { m ->
                addChat(ChatLine("", "${m.name} left", system = true))
                update { it.copy(members = memberList()) }
                sendState()
            }
            is Msg.Ping -> send(Msg.Pong(msg.c, now, from))
            is Msg.Ask -> {
                val who = members[from]?.name ?: "Someone"
                members[from]?.lastSeen = now
                if (msg.action == "add") {
                    val t = msg.track ?: return
                    t.station?.let(Radio::played)
                    p.addToQueue(t.toSong())
                    addChat(ChatLine("", "$who added ${t.title}", system = true))
                    return
                }
                if (!open) return
                when (msg.action) {
                    "play" -> p.play()
                    "pause" -> p.pause()
                    "seek" -> p.seekTo(msg.pos)
                    "next" -> p.skipNext()
                    "prev" -> p.skipPrevious()
                }
            }
            is Msg.Chat -> addChat(ChatLine(members[from]?.name ?: "Someone", msg.text.take(300)))
            else -> Unit
        }
    }

    fun setOpen(on: Boolean) {
        if (!hosting) return
        open = on
        update { it.copy(open = on) }
        sendState()
    }

    // ---------------- guest ----------------

    private fun startGuest() {
        update { it.copy(phase = Phase.Connecting) }
        jobs += main.launch {
            // Knock until the host answers, then check in now and then.
            while (isActive && hostKey == null) {
                if (pool.connected.value > 0) send(Msg.Hello(myName))
                delay(2_000)
            }
            while (isActive) {
                delay(HERE_MS)
                send(Msg.Here(myName))
            }
        }
        jobs += main.launch {
            while (isActive && hostKey == null) delay(250)
            while (isActive) {
                send(Msg.Ping(System.currentTimeMillis()))
                delay(if (bestRtt == Long.MAX_VALUE) 3_000 else 30_000)
            }
        }
        jobs += main.launch {
            while (isActive) {
                delay(1_000)
                val r = state.value ?: continue
                if (r.phase == Phase.Live && System.currentTimeMillis() - lastAt > HOST_TIMEOUT_MS) {
                    update { it.copy(phase = Phase.LostHost) }
                    notice("Lost touch with the host")
                }
                if (r.phase == Phase.Live) last?.let { follow(it, fresh = false) }
            }
        }
    }

    private fun guestHandle(from: String, msg: Msg) {
        val now = System.currentTimeMillis()
        when (msg) {
            is Msg.State -> {
                if (hostKey == null) hostKey = from
                if (from != hostKey) return
                if ((last?.seq ?: -1) >= msg.seq) return
                val first = last == null
                last = msg
                lastAt = now
                update {
                    it.copy(
                        phase = Phase.Live, hostName = msg.host, members = msg.members, open = msg.open,
                        track = msg.track, hostPrivate = msg.private,
                    )
                }
                if (first) addChat(ChatLine("", "You joined ${msg.host}'s room", system = true))
                follow(msg, fresh = true)
            }
            Msg.End -> if (from == hostKey) {
                update { it.copy(phase = Phase.Ended, endedReason = "${it.hostName ?: "The host"} ended the room") }
                stop(saying = false)
            }
            is Msg.Pong -> if (from == hostKey && msg.to == me) {
                val rtt = now - msg.c
                if (rtt in 0..10_000 && rtt <= bestRtt + 50) {
                    bestRtt = minOf(bestRtt, rtt)
                    offset = clockOffset(msg.c, msg.h, now)
                }
            }
            is Msg.Hello -> if (from != hostKey && state.value?.members?.none { it.id == from } == true) addChat(ChatLine("", "${msg.name.take(30)} joined", system = true))
            Msg.Bye -> state.value?.members?.firstOrNull { it.id == from }?.let { addChat(ChatLine("", "${it.name} left", system = true)) }
            is Msg.Chat -> {
                val name = state.value?.members?.firstOrNull { it.id == from }?.name ?: "Someone"
                addChat(ChatLine(name, msg.text.take(300)))
            }
            else -> Unit
        }
    }

    /** Brings this phone in line with the host's [s]. */
    private fun follow(s: Msg.State, fresh: Boolean) {
        val p = player
        if (!p.connected.value) return
        val track = s.track
        if (track == null) {
            if (s.private && p.playWhenReady.value && !holding) p.pause()
            return
        }
        val now = System.currentTimeMillis()
        val target = targetPosition(s, now, offset)
        val current = p.currentSong.value?.videoId
        if (current != track.id) {
            if (!fresh && now < settleUntil) return
            track.station?.let(Radio::played)
            s.next.forEach { t -> t.station?.let(Radio::played) }
            val index = p.queue.value.indexOfFirst { it.videoId == track.id }
            if (index >= 0) p.seekToItem(index, target)
            else p.playAll(listOf(track.toSong()) + s.next.map { it.toSong() }, 0, false, "Listening together", target)
            settleUntil = now + 4_000
            if (!s.playing || holding) p.pause() else p.play()
            return
        }
        // Same song: keep what comes next the same, so the next song starts in step too.
        val after = p.upNext.value.mapNotNull { p.queue.value.getOrNull(it)?.videoId }
        if (fresh && after.take(s.next.size) != s.next.map { it.id }) p.replaceUpcoming(s.next.map { it.toSong() })
        if (holding) return
        if (s.playing) {
            if (!p.playWhenReady.value) p.play()
            if (p.isBuffering.value || now < settleUntil) return
            if (kotlin.math.abs(p.currentPositionMs() - target) > DRIFT_MS) {
                p.seekTo(target)
                settleUntil = now + 2_500
            }
        } else {
            if (p.playWhenReady.value) p.pause()
            if (kotlin.math.abs(p.currentPositionMs() - s.pos) > DRIFT_MS) p.seekTo(s.pos)
        }
    }

    fun intercept(control: Control): Boolean {
        if (hosting) return false
        val r = state.value ?: return false
        if (r.phase != Phase.Live) return false
        if (r.open) {
            val playing = last?.playing == true
            send(
                when (control) {
                    Control.PlayPause -> Msg.Ask(if (playing) "pause" else "play")
                    Control.Next -> Msg.Ask("next")
                    Control.Previous -> Msg.Ask("prev")
                    is Control.Seek -> Msg.Ask("seek", control.positionMs)
                },
            )
            return true
        }
        when (control) {
            Control.PlayPause -> {
                // Without control, play/pause is just for you; the room plays on.
                holding = !holding
                update { it.copy(holding = holding) }
                if (holding) {
                    player.pause()
                    notice("Paused for you. The room keeps playing.")
                } else {
                    settleUntil = 0
                    last?.let { follow(it, fresh = true) }
                }
            }
            else -> notice("Only ${r.hostName ?: "the host"} can change the song")
        }
        return true
    }

    fun suggest(song: Song): Boolean {
        val t = Track.of(song)
        if (t == null) {
            notice("Songs from your phone can't be shared to the room")
            return true
        }
        if (hosting) {
            player.addToQueue(song)
            return true
        }
        if (state.value?.phase != Phase.Live) return false
        send(Msg.Ask("add", track = t))
        notice("Sent \"${song.title}\" to the room")
        return true
    }

    fun say(text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        send(Msg.Chat(t))
        addChat(ChatLine(myName, t, mine = true))
    }
}
