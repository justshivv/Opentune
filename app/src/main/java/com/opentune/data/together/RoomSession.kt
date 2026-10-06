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
import kotlinx.coroutines.withContext

/** What a room needs from the player. */
internal interface RoomPlayer {
    val connected: StateFlow<Boolean>
    val currentSong: StateFlow<Song?>
    val queue: StateFlow<List<Song>>
    val upNext: StateFlow<List<Int>>
    val currentIndex: StateFlow<Int>
    val playWhenReady: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    /** Fires when the position is moved by hand. */
    val seeks: SharedFlow<Unit>
    fun currentPositionMs(): Long
    /** Plays a touch faster or slower than normal; 1 is normal. */
    fun setRate(rate: Float)
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
    override val seeks get() = c.seeks
    override fun currentPositionMs() = c.currentPositionMs()
    override fun setRate(rate: Float) = c.setSyncRate(rate)
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

private const val HEARTBEAT_MS = 2_000L
private const val HERE_MS = 20_000L
private const val MEMBER_TIMEOUT_MS = 50_000L
private const val HOST_TIMEOUT_MS = 25_000L
private const val MAX_CHAT = 100
private const val TAG = "Together"
/**
 * How far a guest may drift before it seeks back into step. Inside this it
 * plays up to [MAX_NUDGE] faster or slower until it's within [IN_STEP_MS],
 * which can't be heard and doesn't stop the music the way a seek does.
 */
internal const val DRIFT_MS = 400L
internal const val IN_STEP_MS = 25L
private const val MAX_NUDGE = 0.05f
/** A drift this size gets the whole [MAX_NUDGE]; smaller ones a share of it. */
private const val NUDGE_FULL_MS = 120f
/** How often a guest checks itself against the host. */
private const val SYNC_TICK_MS = 250L
/** After a seek or a new song, how long to let the player settle before checking again. */
private const val SETTLE_MS = 500L
/** After a guest's own button, how long to wait for the host to catch up before following it again. */
private const val LOCAL_MS = 1_500L
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
    private var rate = 1f
    // How far ahead of the target to seek, to cover the time the seek itself
    // takes; learned from how far off each seek lands.
    private var seekLead = 150L
    private var measureSeek = false
    // A guest's own button, done here at once: until the host's state agrees
    // with it (or [LOCAL_MS] passes), states from before it aren't followed.
    private var localUntil = 0L

    fun start() {
        pool.start()
        jobs += main.launch { pool.connected.collect { n -> update { it.copy(relays = n, phase = if (it.phase == Phase.Connecting && n > 0) (if (hosting) Phase.Live else Phase.FindingHost) else it.phase) } } }
        // Opening a message is AES and JSON (and, the first time, the room
        // key's derivation): done off the main thread, handled on it in order.
        jobs += main.launch {
            pool.events.collect { event ->
                if (event.pubkey == me) return@collect
                val msg = withContext(Dispatchers.Default) { code.open(event.content)?.let(Msg::decode) } ?: return@collect
                if (hosting) hostHandle(event.pubkey, msg) else guestHandle(event.pubkey, msg)
            }
        }
        // Signing is BigInteger work; keep it off the main thread, in order.
        // Not in [jobs]: it has to outlive stop() long enough to send the goodbye.
        CoroutineScope(Dispatchers.Default).launch {
            // The signing table and the room key, ready before the first message.
            Schnorr.warmUp()
            code.seal("")
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
        setRate(1f)
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

    private fun setRate(r: Float) {
        // Changes under half a percent aren't worth a call across to the player service.
        if (kotlin.math.abs(r - rate) < 0.005f && !(r == 1f && rate != 1f)) return
        rate = r
        player.setRate(r)
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
                delay(40) // one message for a burst of changes
                sendState()
            }
        }
        // A seek goes out at once rather than waiting for the check below.
        jobs += main.launch { p.seeks.collect { sendState() } }
        jobs += main.launch {
            while (isActive) {
                delay(500)
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
            while (isActive && hostKey == null) delay(100)
            // A quick burst first, keeping the fastest round trip, so the
            // clock gap is known well within a second or two of joining.
            repeat(6) {
                send(Msg.Ping(System.currentTimeMillis()))
                delay(300)
            }
            while (isActive) {
                delay(15_000)
                send(Msg.Ping(System.currentTimeMillis()))
            }
        }
        jobs += main.launch {
            while (isActive) {
                delay(SYNC_TICK_MS)
                val r = state.value ?: continue
                if (r.phase == Phase.Live && System.currentTimeMillis() - lastAt > HOST_TIMEOUT_MS) {
                    update { it.copy(phase = Phase.LostHost) }
                    notice("Lost touch with the host")
                    setRate(1f)
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

    /**
     * Brings this phone in line with the host's [s]: the same song, playing
     * or not, and the same spot. Run on each state and every [SYNC_TICK_MS].
     * A big gap is a seek, aimed [seekLead] ahead so it lands in step; a
     * small one is closed by playing a touch faster or slower.
     */
    private fun follow(s: Msg.State, fresh: Boolean) {
        val p = player
        if (!p.connected.value) return
        val now = System.currentTimeMillis()
        if (now < localUntil) {
            // Our own button got here first; wait for the host to agree.
            if (agrees(s, now)) localUntil = 0 else return
        }
        val track = s.track
        if (track == null) {
            setRate(1f)
            if (s.private && p.playWhenReady.value && !holding) p.pause()
            return
        }
        val target = targetPosition(s, now, offset)
        val current = p.currentSong.value?.videoId
        if (current != track.id) {
            if (!fresh && now < settleUntil) return
            track.station?.let(Radio::played)
            s.next.forEach { t -> t.station?.let(Radio::played) }
            setRate(1f)
            val start = if (s.playing) target + seekLead else target
            val index = p.queue.value.indexOfFirst { it.videoId == track.id }
            if (index >= 0) p.seekToItem(index, start)
            else p.playAll(listOf(track.toSong()) + s.next.map { it.toSong() }, 0, false, "Listening together", start)
            settleUntil = now + SETTLE_MS
            measureSeek = s.playing
            if (!s.playing || holding) p.pause() else p.play()
            return
        }
        // Same song: keep what comes next the same, so the next song starts in step too.
        val after = p.upNext.value.mapNotNull { p.queue.value.getOrNull(it)?.videoId }
        if (fresh && after.take(s.next.size) != s.next.map { it.id }) p.replaceUpcoming(s.next.map { it.toSong() })
        if (holding) {
            setRate(1f)
            return
        }
        if (s.playing) {
            if (!p.playWhenReady.value) p.play()
            if (p.isBuffering.value || now < settleUntil) return
            val drift = p.currentPositionMs() - target
            if (measureSeek) {
                // Where the last seek landed says how much lead the next one needs.
                measureSeek = false
                seekLead = (seekLead - drift).coerceIn(0L, 3_000L)
            }
            when {
                kotlin.math.abs(drift) > DRIFT_MS -> {
                    setRate(1f)
                    p.seekTo(target + seekLead)
                    settleUntil = now + SETTLE_MS
                    measureSeek = true
                }
                kotlin.math.abs(drift) > IN_STEP_MS -> setRate(1f - MAX_NUDGE * (drift / NUDGE_FULL_MS).coerceIn(-1f, 1f))
                else -> setRate(1f)
            }
        } else {
            setRate(1f)
            if (p.playWhenReady.value) p.pause()
            // Paused, a seek can't be heard, so line up exactly.
            if (kotlin.math.abs(p.currentPositionMs() - s.pos) > 100) p.seekTo(s.pos)
        }
    }

    /** Whether the host's [s] has caught up with what this phone is doing after a button press here. */
    private fun agrees(s: Msg.State, now: Long): Boolean {
        val p = player
        return s.track?.id == p.currentSong.value?.videoId &&
            s.playing == p.playWhenReady.value &&
            kotlin.math.abs(targetPosition(s, now, offset) - p.currentPositionMs()) < 1_500
    }

    fun intercept(control: Control): Boolean {
        if (hosting) return false
        val r = state.value ?: return false
        if (r.phase != Phase.Live) return false
        if (r.open) {
            // Done here at once, so the button answers like it does alone,
            // and asked of the host, who does it for everyone.
            val p = player
            val ask = when (control) {
                Control.PlayPause -> if (p.playWhenReady.value) { p.pause(); Msg.Ask("pause") } else { p.play(); Msg.Ask("play") }
                Control.Next -> { p.skipNext(); Msg.Ask("next") }
                Control.Previous -> { p.skipPrevious(); Msg.Ask("prev") }
                is Control.Seek -> { p.seekTo(control.positionMs); Msg.Ask("seek", control.positionMs) }
            }
            setRate(1f)
            localUntil = System.currentTimeMillis() + LOCAL_MS
            send(ask)
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
