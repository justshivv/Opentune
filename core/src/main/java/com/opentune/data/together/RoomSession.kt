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
    /** Starts [song] now, keeping the rest of the queue after it. */
    fun playNow(song: Song)
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
        // The room's queue, not a list of the listener's own: smart shuffle stays out of it.
        c.playAll(songs, startIndex, shuffle, source, startPositionMs, fromList = false)
    override fun replaceUpcoming(songs: List<Song>) = c.replaceUpcoming(songs)
    override fun addToQueue(song: Song) = c.addToQueue(song)
    override fun playNow(song: Song) {
        c.playNext(song)
        c.skipNext()
    }
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

/** Where a room's voice clips go to be heard as they arrive. */
internal fun interface TalkSink {
    /** More of clip [id], in order; [cfg] is the codec setup, [done] the clip's end. */
    fun talk(id: String, from: String, cfg: ByteArray, frames: List<ByteArray>, done: Boolean)
}

private const val HEARTBEAT_MS = 2_000L
/** A knock not repeated for this long is someone who gave up waiting. */
private const val REQUEST_TIMEOUT_MS = 30_000L
/** Talk with no new piece for this long has stopped. */
private const val TALK_QUIET_MS = 3_000L
/** How often a guest says it's still here. Anything else it sends counts too. */
private const val HERE_MS = 10_000L
/** A guest not heard from for this long has gone: several check-ins, all lost. */
private const val MEMBER_TIMEOUT_MS = 45_000L
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
    /** Whether people who knock wait for the host to let them in. */
    askToJoin: Boolean = true,
    private val talkSink: TalkSink = TalkSink { _, _, _, _, _ -> },
    private val hereMs: Long = HERE_MS,
    private val memberTimeoutMs: Long = MEMBER_TIMEOUT_MS,
) {
    private val key = Schnorr.newPrivateKey()
    private val me = Schnorr.publicKey(key).toHex()
    /**
     * The key the room's own messages travel under. The host makes it; a
     * guest gets it only when the host lets them in. The code opens just the
     * lobby, so someone with only the code can knock but can't listen in.
     */
    @Volatile private var roomKey: RoomKey? = if (hosting) RoomKey.random(code.tag) else null
    /** Goes up each time the host changes the key, after putting someone out. */
    private var epoch = 0
    private var askToJoin = askToJoin
    private val requests = linkedMapOf<String, Member0>()
    private val outbox = Channel<Msg>(Channel.BUFFERED)
    private val jobs = mutableListOf<Job>()
    private val members = linkedMapOf<String, Member0>()
    /**
     * For the host: guests dropped for not being heard from, by name. They
     * still hold the room key, so when they turn up again they're simply
     * back, without knocking and waiting again.
     */
    private val dropped = linkedMapOf<String, String>()

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
        update { it.copy(myId = me) }
        pool.start()
        jobs += main.launch { pool.connected.collect { n -> update { it.copy(relays = n, phase = if (it.phase == Phase.Connecting && n > 0) (if (hosting) Phase.Live else Phase.FindingHost) else it.phase) } } }
        // Opening a message is AES and JSON (and, the first time, the room
        // key's derivation): done off the main thread, handled on it in order.
        jobs += main.launch {
            pool.events.collect { event ->
                if (event.pubkey == me) return@collect
                val opened = withContext(Dispatchers.Default) {
                    roomKey?.open(event.content)?.let { Msg.decode(it)?.to(true) }
                        ?: code.open(event.content)?.let { Msg.decode(it)?.to(false) }
                } ?: return@collect
                val (msg, inRoom) = opened
                // Only knocking and the host's answers travel under the code; anything
                // else sealed that way is from someone who wasn't let in.
                if (!inRoom && !Msg.inLobby(msg)) return@collect
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
                val text = Msg.encode(m)
                // A guest not yet let in has no room key, and nothing to say in the room.
                val content = if (Msg.inLobby(m)) code.seal(text) else roomKey?.seal(text) ?: continue
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
        update { it.copy(hostName = myName, members = memberList(), askToJoin = askToJoin, phase = if (pool.connected.value > 0) Phase.Live else Phase.Connecting) }
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
                if (requests.values.removeAll { now - it.lastSeen > REQUEST_TIMEOUT_MS }) update { it.copy(requests = requestList()) }
                val gone = members.filterValues { now - it.lastSeen > memberTimeoutMs }
                if (gone.isNotEmpty()) {
                    gone.forEach { (id, m) -> members.remove(id); dropped[id] = m.name }
                    while (dropped.size > 50) dropped.remove(dropped.keys.first())
                    gone.values.forEach { addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} left", it.name), system = true)) }
                    update { it.copy(members = memberList()) }
                }
            }
        }
    }

    private fun memberList() = listOf(Member(me, myName, host = true)) + members.map { (id, m) -> Member(id, m.name) }
    private fun requestList() = requests.map { (id, m) -> Member(id, m.name) }

    /** Hands [id] the room key, sealed so only their phone can open it. */
    private fun admit(id: String) {
        val wrapped = runCatching { roomKey?.wrapFor(key, id.hexToBytes()) }.getOrNull() ?: return
        send(Msg.Admit(id, wrapped, epoch, myName))
    }

    private fun letIn(id: String, name: String, now: Long) {
        requests.remove(id)
        members[id] = Member0(name, now)
        admit(id)
        addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} joined", name), system = true))
        update { it.copy(members = memberList(), requests = requestList()) }
        sendState()
    }

    /** [id], dropped for silence, turned up again: back in, with the key and the state. */
    private fun rejoin(id: String, name: String, now: Long) {
        dropped.remove(id)
        requests.remove(id)
        members[id] = Member0(name, now)
        admit(id)
        addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} is back", name), system = true))
        update { it.copy(members = memberList(), requests = requestList()) }
        sendState()
    }

    fun approve(id: String) {
        if (!hosting) return
        val r = requests[id] ?: return
        letIn(id, r.name, System.currentTimeMillis())
    }

    fun reject(id: String) {
        if (!hosting) return
        requests.remove(id) ?: return
        send(Msg.Deny(id, host = myName))
        update { it.copy(requests = requestList()) }
    }

    /**
     * Puts [id] out of the room. The room moves to a new key, handed to
     * everyone else, so the old one they hold opens nothing from now on.
     */
    fun remove(id: String) {
        if (!hosting) return
        val m = members.remove(id) ?: return
        roomKey = RoomKey.random(code.tag)
        epoch++
        send(Msg.Deny(id, removed = true, host = myName))
        members.keys.forEach(::admit)
        addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} was removed", m.name), system = true))
        update { it.copy(members = memberList()) }
        sendState()
    }

    fun setAskToJoin(on: Boolean) {
        if (!hosting) return
        askToJoin = on
        update { it.copy(askToJoin = on) }
        // Nobody needs to wait any more: everyone knocking comes in.
        if (!on) requests.entries.toList().forEach { (id, r) -> letIn(id, r.name, System.currentTimeMillis()) }
    }

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
        // Inside the room, only people let in are listened to. Someone dropped
        // for silence who still talks in the room never really left.
        if (!Msg.inLobby(msg) && from !in members) {
            val name = dropped[from] ?: return
            rejoin(from, name, now)
        }
        // Anything a guest sends shows it's still here, not just its check-ins.
        members[from]?.lastSeen = now
        when (msg) {
            is Msg.Hello -> {
                val name = msg.name.take(30)
                val member = members[from]
                when {
                    // In already, and knocking again: it missed the key or the state.
                    member != null -> { member.lastSeen = now; admit(from); sendState() }
                    from in dropped -> rejoin(from, name, now)
                    !askToJoin -> letIn(from, name, now)
                    else -> {
                        val known = requests[from]
                        if (known == null) {
                            requests[from] = Member0(name, now)
                            update { it.copy(requests = requestList()) }
                            notice(com.opentune.i18n.Tr.f("{0} wants to join", name))
                        } else known.lastSeen = now
                        send(Msg.Pending(from, myName))
                    }
                }
            }
            is Msg.Here -> members[from]?.lastSeen = now
            // A goodbye is signed by whoever leaves, so it can't be faked for someone else.
            Msg.Bye -> if (requests.remove(from) != null) update { it.copy(requests = requestList()) } else members.remove(from)?.let { m ->
                addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} left", m.name), system = true))
                update { it.copy(members = memberList()) }
                sendState()
            }
            is Msg.Ping -> send(Msg.Pong(msg.c, now, from))
            is Msg.Ask -> {
                val who = members[from]?.name ?: "Someone"
                members[from]?.lastSeen = now
                if (msg.action == "add" || (msg.action == "now" && !open)) {
                    val t = msg.track ?: return
                    t.station?.let(Radio::played)
                    p.addToQueue(t.toSong())
                    addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} added {1}", who, t.title), system = true))
                    return
                }
                if (msg.action == "now") {
                    // Guests may control playback: their pick starts for everyone.
                    val t = msg.track ?: return
                    t.station?.let(Radio::played)
                    p.playNow(t.toSong())
                    addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} played {1}", who, t.title), system = true))
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
            is Msg.Chat -> addChat(ChatLine(members[from]?.name ?: "Someone", msg.text.take(300), fromId = from))
            is Msg.Voice -> voiceIn(from, msg)
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
            // Knock until the host lists this phone among the members, then
            // check in now and then. Hearing from the host isn't enough: the
            // host's state can arrive before the host has heard our hello.
            // Knocking starts again whenever the host's list leaves this phone
            // out (it stopped hearing us) or the host goes quiet.
            var lastHere = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                val listed = roomKey != null && last?.members?.any { it.id == me } == true
                val lost = state.value?.phase == Phase.LostHost
                if (pool.connected.value > 0) {
                    if (!listed || lost) send(Msg.Hello(myName))
                    else if (now - lastHere >= hereMs) { send(Msg.Here(myName)); lastHere = now }
                }
                delay(minOf(2_000L, hereMs))
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
            is Msg.Pending -> if (msg.to == me && roomKey == null && (hostKey == null || from == hostKey)) {
                hostKey = from
                update { it.copy(phase = Phase.Waiting, hostName = msg.host) }
            }
            is Msg.Admit -> if (msg.to == me && (hostKey == null || from == hostKey) && msg.epoch >= epoch) {
                val k = runCatching { RoomKey.unwrap(msg.key, key, from.hexToBytes(), code.tag) }.getOrNull() ?: return
                hostKey = from
                roomKey = k
                epoch = msg.epoch
                update { if (it.phase == Phase.Waiting || it.phase == Phase.Connecting) it.copy(phase = Phase.FindingHost, hostName = msg.host) else it }
            }
            is Msg.Deny -> if (msg.to == me && (hostKey == null || from == hostKey)) {
                val host = msg.host.ifBlank { state.value?.hostName ?: "The host" }
                update { it.copy(phase = Phase.Ended, endedReason = if (msg.removed) com.opentune.i18n.Tr.f("{0} removed you from the room", host) else com.opentune.i18n.Tr.f("{0} didn't let you in", host)) }
                stop(saying = false)
            }
            is Msg.State -> {
                if (hostKey == null) hostKey = from
                if (from != hostKey) return
                if ((last?.seq ?: -1) >= msg.seq) return
                val first = last == null
                val before = last?.members?.map { it.id }?.toSet()
                last = msg
                lastAt = now
                update {
                    it.copy(
                        phase = Phase.Live, hostName = msg.host, members = withMe(msg.members), open = msg.open,
                        track = msg.track, hostPrivate = msg.private,
                    )
                }
                if (first) addChat(ChatLine("", com.opentune.i18n.Tr.f("You joined {0}'s room", msg.host), system = true))
                else if (before != null) {
                    // Comings and goings, from the host's list of who's in.
                    msg.members.filter { it.id !in before && it.id != me }.forEach { addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} joined", it.name), system = true)) }
                    val now2 = msg.members.map { it.id }.toSet()
                    state.value?.members?.filter { it.id in before && it.id !in now2 && it.id != me }?.forEach { addChat(ChatLine("", com.opentune.i18n.Tr.f("{0} left", it.name), system = true)) }
                }
                follow(msg, fresh = true)
            }
            Msg.End -> if (from == hostKey) {
                update { it.copy(phase = Phase.Ended, endedReason = com.opentune.i18n.Tr.f("{0} ended the room", it.hostName ?: "The host")) }
                stop(saying = false)
            }
            is Msg.Pong -> if (from == hostKey && msg.to == me) {
                val rtt = now - msg.c
                if (rtt in 0..10_000 && rtt <= bestRtt + 50) {
                    bestRtt = minOf(bestRtt, rtt)
                    offset = clockOffset(msg.c, msg.h, now)
                }
            }
            is Msg.Chat -> addChat(ChatLine(nameOf(from), msg.text.take(300), fromId = from))
            is Msg.Voice -> voiceIn(from, msg)
            else -> Unit
        }
    }

    /** The host's member list, with this phone in it even before the host has heard it arrive. */
    private fun withMe(members: List<Member>) =
        if (members.any { it.id == me }) members else members + Member(me, myName)

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
            else -> notice(com.opentune.i18n.Tr.f("Only {0} can change the song", r.hostName ?: "the host"))
        }
        return true
    }

    /**
     * A song picked on this phone. A guest's goes into the room's queue, or,
     * with [now] and the host letting guests control playback, starts for
     * everyone.
     */
    fun suggest(song: Song, now: Boolean = false): Boolean {
        val t = Track.of(song)
        if (t == null) {
            notice("Songs from your phone can't be shared to the room")
            return true
        }
        if (hosting) {
            player.addToQueue(song)
            return true
        }
        val r = state.value ?: return false
        if (r.phase != Phase.Live) return false
        when {
            now && r.open -> {
                send(Msg.Ask("now", track = t))
                notice(com.opentune.i18n.Tr.f("Playing \"{0}\" for everyone", song.title))
            }
            now -> {
                send(Msg.Ask("add", track = t))
                notice(com.opentune.i18n.Tr.f("Added \"{0}\" to the queue. {1} hasn't let guests control playback", song.title, r.hostName ?: "The host"))
            }
            else -> {
                send(Msg.Ask("add", track = t))
                notice(com.opentune.i18n.Tr.f("Sent \"{0}\" to the room", song.title))
            }
        }
        return true
    }

    fun say(text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        if (roomKey == null) { notice("You can chat once you're let in"); return }
        send(Msg.Chat(t))
        addChat(ChatLine(myName, t, mine = true, fromId = me))
    }

    private fun nameOf(id: String): String =
        if (hosting) members[id]?.name ?: "Someone" else state.value?.members?.firstOrNull { it.id == id }?.name ?: "Someone"

    // ---------------- voice ----------------

    /** A clip coming in, piece by piece; pieces can arrive out of order. */
    private class Incoming(val live: Boolean) {
        var cfg: ByteArray? = null
        val parts = java.util.TreeMap<Int, List<ByteArray>>()
        var lastSeq: Int? = null
        var ms = 0L
        /** For talk: the next piece to hand on to be heard. */
        var played = 0
        val complete get() = lastSeq != null && parts.size == lastSeq!! + 1
        fun frames() = parts.values.flatten()
    }

    private val incoming = mutableMapOf<String, Incoming>()

    private fun voiceIn(from: String, v: Msg.Voice) {
        val frames = runCatching { VoiceFrames.unpack(java.util.Base64.getDecoder().decode(v.data)) }.getOrNull() ?: return
        val clipId = "$from/${v.id}"
        if (incoming.size > 32) incoming.keys.take(incoming.size - 32).forEach(incoming::remove)
        val c = incoming.getOrPut(clipId) { Incoming(v.live) }
        if (v.seq in c.parts) return
        v.cfg?.let { c.cfg = runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        c.parts[v.seq] = frames
        if (v.last) c.lastSeq = v.seq
        c.ms = maxOf(c.ms, v.ms)
        val cfg = c.cfg
        if (c.live && cfg != null) {
            // Talk is heard as it comes, in order.
            while (c.parts.containsKey(c.played)) {
                val done = c.lastSeq == c.played
                talkSink.talk(clipId, from, cfg, c.parts.getValue(c.played), done)
                c.played++
            }
        }
        if (c.live) {
            // Talk is only heard, as it happens; it doesn't stay in the chat.
            talking(from, !c.complete)
            if (c.complete) incoming.remove(clipId)
        } else if (c.complete) {
            showClip(clipId, nameOf(from), from, c.cfg, c.frames(), c.ms, complete = true, live = false, mine = false)
        }
    }

    private val talkSeen = mutableMapOf<String, Long>()

    /** Who's talking now, by member id, for the room screen; a talker gone quiet for a few seconds has stopped. */
    private fun talking(from: String, on: Boolean) {
        if (on) {
            val at = System.currentTimeMillis()
            talkSeen[from] = at
            update { if (from in it.talking) it else it.copy(talking = it.talking + from) }
            main.launch {
                delay(TALK_QUIET_MS)
                if (talkSeen[from] == at) talking(from, false)
            }
        } else {
            talkSeen.remove(from)
            update { it.copy(talking = it.talking - from) }
        }
    }

    private fun showClip(id: String, name: String, from: String, cfg: ByteArray?, frames: List<ByteArray>, ms: Long, complete: Boolean, live: Boolean, mine: Boolean) {
        val clip = Together.VoiceClip(id, cfg, frames, ms, complete, live)
        update { r ->
            val i = r.chat.indexOfFirst { it.voice?.id == id }
            val line = ChatLine(name, "", mine = mine, fromId = from, voice = clip)
            r.copy(chat = if (i >= 0) r.chat.toMutableList().also { it[i] = line.copy(at = r.chat[i].at) } else (r.chat + line).takeLast(MAX_CHAT))
        }
    }

    /**
     * Sends a piece of this phone's voice clip [id]: [frames] in order,
     * [cfg] with the first piece. Shows in this phone's chat as well.
     */
    fun sendVoice(id: String, seq: Int, cfg: ByteArray?, frames: List<ByteArray>, ms: Long, last: Boolean, live: Boolean, allFrames: List<ByteArray>, allCfg: ByteArray?) {
        if (roomKey == null) return
        send(Msg.Voice(id, seq, java.util.Base64.getEncoder().encodeToString(VoiceFrames.pack(frames)), last, live,
            cfg?.let { java.util.Base64.getEncoder().encodeToString(it) }, ms))
        // Only a voice note shows in the chat; talk is heard live and gone.
        if (!live && last) showClip("$me/$id", myName, me, allCfg, allFrames, ms, complete = true, live = false, mine = true)
    }
}
