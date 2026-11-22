package com.opentune.data.connect

import com.opentune.data.DebugLog as Log
import com.opentune.data.connect.ConnectMsg.Ack
import com.opentune.data.connect.ConnectMsg.Command
import com.opentune.data.connect.ConnectMsg.Leave
import com.opentune.data.connect.ConnectMsg.Ping
import com.opentune.data.connect.ConnectMsg.Pong
import com.opentune.data.connect.ConnectMsg.Presence
import com.opentune.data.connect.ConnectMsg.State
import com.opentune.data.connect.ConnectMsg.Transfer
import com.opentune.data.together.NostrEvent
import com.opentune.data.together.RoomTransport
import com.opentune.data.together.Schnorr
import com.opentune.data.together.toHex
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Connect needs from this device's player. */
internal interface ConnectPlayer {
    /** What's loaded now, or null when nothing is. */
    fun snapshot(): Snapshot?
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun setVolume(v: Float)
    fun setShuffle(on: Boolean)
    /** "off", "all" or "one". */
    fun setRepeat(mode: String)
    fun playTrack(t: CTrack)
    fun addToQueue(ts: List<CTrack>)
    /**
     * Plays, or takes out, entry [at] of the queue this device last told
     * ([Snapshot.queue]) when it's still [id]; otherwise the first one that
     * is. False when there's none.
     */
    fun playQueued(at: Int, id: String): Boolean
    fun removeQueued(at: Int, id: String): Boolean
    /** Loads [queue] from [index] at [pos] ms; false when none of it can play here. */
    fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean
}

data class Snapshot(
    val track: CTrack?,
    /** Something only this device can play (its own files, a private server). */
    val private: Boolean,
    /** Moving: the clock other devices run on for this one. Not while it stalls to load. */
    val playing: Boolean,
    val pos: Long,
    /** What comes after [track]. */
    val queue: List<CTrack>,
    val volume: Float,
    val shuffle: Boolean,
    val repeat: String,
    /** How it sounds here, in this device's words; null when it can't say. */
    val quality: String? = null,
    /** Meant to play, stalled or not: starting to play is judged by this, so a stall ending isn't a start. */
    val wants: Boolean? = null,
)

/**
 * Meant to play, stalled to load or not: who has the music is judged by this,
 * and play/pause buttons for the device go by it. A device too old to say
 * ([State.wants] missing) is taken at its [State.playing].
 */
val State.meant: Boolean get() = wants ?: playing

/**
 * Another of the person's devices, as last heard. [receivedAt] is when, on
 * this device's clock, its [state] was true (the moment it was sent, as near
 * as the clocks' difference is known); [since] when it took the music (see
 * [State.since]), also on this device's clock.
 */
data class Device(
    val id: String,
    val name: String,
    val form: String,
    val lastSeen: Long,
    val state: State? = null,
    val receivedAt: Long = 0,
    val since: Long = 0,
) {
    /** Where its song is now, counting on from its last state while it moves. */
    fun positionAt(now: Long): Long = state?.let { if (it.playing) it.pos + (now - receivedAt).coerceAtLeast(0) else it.pos } ?: 0

    val hasSomething get() = state != null && (state.track != null || state.private)
}

data class ConnectView(
    val myId: String,
    val myName: String,
    val relays: Int = 0,
    /** The other devices here now. */
    val devices: List<Device> = emptyList(),
    /** The device whose playback the player shows: this one's id, another's, or null when nothing's loaded anywhere. */
    val activeId: String? = null,
    /** Commands sent and not yet answered, by id. */
    val pending: Set<String> = emptySet(),
) {
    /** The other device this one is showing and controlling, if any. */
    val controlling: Device? get() = activeId?.takeIf { it != myId }?.let { id -> devices.firstOrNull { it.id == id } }
}

/** How often things happen; shorter in tests. */
internal data class Timings(
    val presenceMs: Long = 30_000,
    val goneMs: Long = 75_000,
    val stateMs: Long = 5_000,
    val commandMs: Long = 5_000,
    val transferMs: Long = 8_000,
    val tickMs: Long = 1_000,
)

/**
 * One device's part in its group: says it's here, tells the others what it
 * plays, follows what they play, and passes commands and handovers between
 * them. Every device is equal; whichever plays is the one the others show.
 *
 * Runs on [scope] (the main thread in the app). Sealing and signing happen
 * on a background thread, in order.
 */
internal class ConnectSession(
    val group: ConnectGroup,
    val myId: String,
    private var myName: String,
    private val form: String,
    private val player: ConnectPlayer,
    private val transport: RoomTransport,
    private val view: MutableStateFlow<ConnectView>,
    /** A message for the person, as an English template and the device name for its {0}. */
    private val notice: (template: String, name: String) -> Unit,
    private val scope: CoroutineScope,
    private val timings: Timings = Timings(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Another device here moved to the group with this secret: this one follows. */
    private val moved: (secret: String) -> Unit = {},
    /**
     * Groups this device was in before: a device still there (it was off
     * when the groups were merged) is told where everyone went.
     */
    private val former: List<ConnectGroup> = emptyList(),
    /**
     * When this device last took the music, on its clock, as an earlier
     * session here left it ([tookAt]); 0 when there was none. Connect
     * starting over doesn't count as taking it.
     */
    private val took: Long = 0,
) {
    private val key = Schnorr.newPrivateKey()
    private val myPub = Schnorr.publicKey(key).toHex()
    private val outbox = Channel<Pair<ConnectMsg, ConnectGroup>>(Channel.UNLIMITED)
    /** When each device left behind in a former group was last told to follow. */
    private val forwarded = HashMap<String, Long>()
    private val jobs = mutableListOf<Job>()
    private var seq = 0L
    private val devices = LinkedHashMap<String, Device>()

    // Relays deliver late, out of order and more than once. Each message is taken once; a report
    // or a presence only over an older one of its kind, and a command or handover for this device
    // only over older ones, so a late copy never undoes what came after it. An answer counts
    // whenever it comes: what followed it can't make it stale.
    private val seen = object : LinkedHashMap<String, Unit>(256, .75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 2_000
    }
    private val lastSeq = HashMap<String, Long>()
    private val lastState = HashMap<String, Long>()
    private val lastPresence = HashMap<String, Long>()
    private val lastControl = HashMap<String, Long>()
    /** The seq of each device's goodbye: anything it said before that is over. */
    private val leftAt = HashMap<String, Long>()

    /**
     * Each other device's clock against this one's ([Offset.offset] = theirs
     * minus mine), measured over the relays' round trip; the one from the
     * quickest trip is kept, as the most exact.
     */
    private class Offset(val offset: Long, val rtt: Long, val at: Long)
    private val offsets = HashMap<String, Offset>()
    private class Pinged(val at: Long, val unanswered: Int)
    private val pinged = HashMap<String, Pinged>()

    private var mine: Snapshot? = null
    private var mineAt = 0L
    private var lastStateSent = 0L
    private var lastPresenceSent = 0L

    /** When this device took the music, for the next session here to carry on from. */
    val tookAt: Long get() = mineAt

    private class Pending(val to: String, val job: Job, val onAck: (Ack) -> Unit)
    private val pending = HashMap<String, Pending>()
    /**
     * Pressed here for a device a moment ago: play/pause, shuffle and repeat
     * until [until]; a place in a song (a seek, a skip, and the song skipped
     * to as [track]) until [placeUntil], set at [placeAt].
     */
    private data class Guess(
        val until: Long = 0,
        val playing: Boolean? = null,
        val shuffle: Boolean? = null,
        val repeat: String? = null,
        val placeUntil: Long = 0,
        val placeAt: Long = 0,
        val pos: Long? = null,
        val track: String? = null,
    )
    private val guesses = HashMap<String, Guess>()

    /** Notes a change pressed here for [id], on top of the ones still waiting to show in its reports. */
    private fun guess(id: String, playing: Boolean? = null, shuffle: Boolean? = null, repeat: String? = null) {
        val now = clock()
        val before = guesses[id] ?: Guess()
        val live = now < before.until
        guesses[id] = before.copy(
            until = now + GUESS_MS,
            playing = playing ?: before.playing.takeIf { live },
            shuffle = shuffle ?: before.shuffle.takeIf { live },
            repeat = repeat ?: before.repeat.takeIf { live },
        )
    }

    /** Notes a place pressed here for [id]: [pos] in [track] (null: the song it's on). */
    private fun guessPlace(id: String, pos: Long, track: String? = null) {
        val now = clock()
        guesses[id] = (guesses[id] ?: Guess()).copy(placeUntil = now + PLACE_MS, placeAt = now, pos = pos, track = track)
    }

    /** What's meant here: [Snapshot.wants], or moving for a player that can't say. */
    private fun meant(s: Snapshot?) = s?.wants ?: s?.playing ?: false
    private fun has(s: Snapshot?) = s != null && (s.track != null || s.private)

    /** How long ago, on this device's clock, [msg] was sent; 0 while the sender's clock isn't known. */
    private fun transit(msg: ConnectMsg, now: Long): Long {
        val o = offsets[msg.from] ?: return 0
        return (now - (msg.at - o.offset)).coerceIn(0, MAX_TRANSIT_MS)
    }

    /** Theirs minus mine, once a round trip has measured it; for tests. */
    internal fun clockOffset(id: String): Long? = offsets[id]?.offset

    fun start() {
        // Numbered from the clock, so a restarted device's messages still count as newer than its last ones.
        seq = maxOf(seq, clock())
        view.value = ConnectView(myId, myName)
        transport.start()
        jobs += scope.launch {
            transport.connected.collect { n ->
                // What was said with no relay connected (at start, or after losing them all) went nowhere: said again.
                val reached = view.value.relays == 0 && n > 0
                view.value = view.value.copy(relays = n)
                if (reached) { sayPresence(); sayState() }
            }
        }
        jobs += scope.launch {
            transport.events.collect { event ->
                if (event.pubkey == myPub) return@collect
                val (msg, from) = withContext(Dispatchers.Default) {
                    group.open(event.content)?.let(ConnectMsg::decode)?.let { it to group }
                        ?: former.firstNotNullOfOrNull { g -> g.open(event.content)?.let(ConnectMsg::decode)?.let { it to g } }
                } ?: return@collect
                if (from === group) handle(msg) else forward(msg, from)
            }
        }
        // Not in [jobs]: it has to outlive stop() long enough to send the goodbye.
        scope.launch(Dispatchers.Default) {
            for ((m, g) in outbox) {
                val event = NostrEvent.signed(key, KIND, listOf(listOf("t", g.tag)), ConnectMsg.fit(m, g))
                if (!transport.publish(event)) Log.w(TAG, "no relay connected for ${m::class.simpleName}")
            }
        }
        jobs += scope.launch {
            while (isActive) {
                tick()
                delay(timings.tickMs)
            }
        }
        mine = player.snapshot()
        // Connect starting over isn't taking the music: when it was taken here is carried on.
        // Only a device starting from nothing (the app opened afresh) counts its song from now.
        if (has(mine)) mineAt = if (took > 0) took else clock()
        sayPresence()
        sayState()
    }

    /** Leaves the group; [next] (another group's secret) takes the devices here along. */
    fun stop(next: String? = null) {
        send(Leave(myId, ++seq, clock(), next = next))
        jobs.forEach { it.cancel() }
        pending.values.forEach { it.job.cancel() }
        scope.launch {
            delay(600) // let the goodbye go out
            outbox.close()
            transport.close()
        }
    }

    fun rename(name: String) {
        myName = name
        view.value = view.value.copy(myName = name)
        sayPresence()
    }

    /** This device's player changed: tell the others, and quiet whichever other one was playing. */
    fun localChanged() {
        val before = mine
        val now = player.snapshot()
        mine = now
        if (now == null) { refresh(); return }
        val started = meant(now) && !meant(before)
        val newSong = now.track != before?.track || now.private != before?.private
        // Taking the music: starting it, or a new song while it's meant to play. Pausing leaves
        // it where it was taken, and so does a song put on paused (a queue restored, a list edited).
        if (started || (newSong && meant(now)) || (mineAt == 0L && has(now))) mineAt = clock()
        if (started) {
            // Stalled or not: a device still meant to play would otherwise start up again.
            for (d in devices.values) if (d.state?.meant == true) command(d.id, "pause", quiet = true)
        }
        sayState()
        refresh()
    }

    /**
     * A button pressed here while another device is the one playing: sends it
     * there instead. True when it went, so the caller does nothing here.
     */
    fun intercept(action: String, pos: Long = 0, value: Float = 0f, mode: String? = null): Boolean {
        val target = view.value.controlling ?: return false
        // Show the change at once; the target's own state confirms it.
        val shown = target.state
        if (shown != null) {
            val now = clock()
            val next = shown.queue.firstOrNull()
            val guess = when (action) {
                "play" -> shown.copy(playing = true, wants = true, pos = target.positionAt(now))
                "pause" -> shown.copy(playing = false, wants = false, pos = target.positionAt(now))
                "seek" -> shown.copy(pos = pos)
                // A skip shows where it lands at once, standing until the song there starts: the
                // last song's place, counted on against the new one, ran ahead until a report came.
                "next" -> next?.let { shown.copy(track = it, queue = shown.queue.drop(1), pos = 0, playing = false) }
                "previous" -> shown.copy(pos = 0, playing = false)
                "volume" -> shown.copy(volume = value)
                "shuffle" -> shown.copy(shuffle = value > 0.5f)
                "repeat" -> shown.copy(repeat = mode ?: shown.repeat)
                else -> null
            }
            if (guess != null) setDevice(target.copy(state = guess, receivedAt = now))
            if (guess != null && (action == "play" || action == "pause")) guess(target.id, playing = guess.meant)
            if (guess != null && action == "shuffle") guess(target.id, shuffle = guess.shuffle)
            if (guess != null && action == "repeat") guess(target.id, repeat = guess.repeat)
            if (guess != null && action == "seek") guessPlace(target.id, pos)
            if (guess != null && action == "next") guessPlace(target.id, 0, next?.id)
            if (guess != null && action == "previous") guessPlace(target.id, 0)
        }
        command(target.id, action, pos = pos, value = value, mode = mode) { ack -> if (!ack.ok) undoGuess(target, shown) }
        return true
    }

    /** A change shown at once that [target] didn't make: back to what it said last. */
    private fun undoGuess(target: Device, shown: State?) {
        guesses.remove(target.id)
        if (shown != null) devices[target.id]?.let { setDevice(it.copy(state = shown, receivedAt = target.receivedAt)) }
    }

    /** [track] starting from its beginning on [deviceId], shown at once: the queue up to it goes. */
    private fun showStarting(deviceId: String, track: CTrack): Device? {
        val target = devices[deviceId] ?: return null
        val shown = target.state ?: return null
        val at = shown.queue.indexOfFirst { it.id == track.id }
        setDevice(target.copy(state = shown.copy(track = track, queue = if (at >= 0) shown.queue.drop(at + 1) else shown.queue, pos = 0, playing = false), receivedAt = clock()))
        guessPlace(deviceId, 0, track.id)
        return target
    }

    fun playTrackOn(deviceId: String, track: CTrack) {
        val before = showStarting(deviceId, track)
        command(deviceId, "playTrack", track = track) { ack -> if (!ack.ok && before != null) undoGuess(before, before.state) }
    }
    fun addToQueueOn(deviceId: String, tracks: List<CTrack>) = command(deviceId, "addToQueue", tracks = tracks)

    /** Plays entry [at] of [deviceId]'s Up Next ([track], so a queue changed meanwhile still finds it). */
    fun jumpOn(deviceId: String, at: Int, track: CTrack) {
        val before = showStarting(deviceId, track)
        command(deviceId, "jump", pos = at.toLong(), track = track) { ack -> if (!ack.ok && before != null) undoGuess(before, before.state) }
    }
    /** Takes entry [at] ([track]) out of [deviceId]'s Up Next. */
    fun removeOn(deviceId: String, at: Int, track: CTrack) = command(deviceId, "remove", pos = at.toLong(), track = track)

    /**
     * Moves playback to [deviceId] (this device's own id pulls it here). The
     * device playing now stops once the new one answers that it's playing.
     */
    fun transferTo(deviceId: String) {
        val activeId = view.value.activeId ?: return
        if (deviceId == activeId) return
        if (activeId == myId) {
            val snap = player.snapshot()?.takeIf { it.track != null } ?: return
            val queue = listOf(snap.track!!) + snap.queue
            val id = newId()
            val sentAt = clock()
            // Meant to play goes over as playing: a stall here at that moment isn't a pause.
            send(Transfer(myId, ++seq, sentAt, to = deviceId, id = id, queue = queue, index = 0, pos = snap.pos, playing = meant(snap)))
            expect(id, deviceId, timings.transferMs, answered = { tookOver(deviceId, snap.track, sentAt) }) { ack ->
                if (ack.ok) player.pause() else refused(ack, deviceId)
            }
            return
        }
        val source = devices[activeId] ?: return
        val state = source.state ?: return
        val track = state.track ?: run { notice(CANT_PLAY, nameOf(deviceId)); return }
        val queue = listOf(track) + state.queue
        val now = clock()
        val pos = source.positionAt(now)
        if (deviceId == myId) {
            if (player.load(queue, 0, pos, state.meant)) {
                command(source.id, "pause", quiet = true)
                mine = null
                mineAt = now
                localChanged()
            } else notice(CANT_PLAY, myName)
            return
        }
        val id = newId()
        send(Transfer(myId, ++seq, now, to = deviceId, id = id, queue = queue, index = 0, pos = pos, playing = state.meant))
        expect(id, deviceId, timings.transferMs, answered = { tookOver(deviceId, track, now) }) { ack ->
            if (ack.ok) command(source.id, "pause", quiet = true) else refused(ack, deviceId)
        }
    }

    /** Whether [deviceId] says it has [track] and took it after [after]: a handover it took, its answer lost. */
    private fun tookOver(deviceId: String, track: CTrack?, after: Long): Boolean {
        val d = devices[deviceId] ?: return false
        return d.state?.track == track && d.since >= after - TOOK_SLACK_MS
    }

    /** Why [deviceId] didn't take the music, for the person. */
    private fun refused(ack: Ack, deviceId: String) = notice(if (ack.reason == "needs-tap") NEEDS_TAP else CANT_PLAY, nameOf(deviceId))

    // ---- In --------------------------------------------------------------------------------

    /** Whether [msg] is newer than the last of its kind from its sender in [last]; noted if so. */
    private fun newer(last: HashMap<String, Long>, msg: ConnectMsg): Boolean {
        val before = last[msg.from]
        if (before != null && msg.seq <= before) return false
        last[msg.from] = msg.seq
        return true
    }

    internal fun handle(msg: ConnectMsg) {
        if (msg.from == myId) return
        // Another relay's copy, or one heard again after a relay came back.
        if (seen.put("${msg.from}/${msg.seq}", Unit) != null) return
        // Said before the device said goodbye: over.
        leftAt[msg.from]?.let { if (msg.seq <= it) return }
        val latest = lastSeq[msg.from] ?: Long.MIN_VALUE
        lastSeq[msg.from] = maxOf(latest, msg.seq)
        val now = clock()
        val known = devices[msg.from]
        when (msg) {
            is Presence -> {
                if (!newer(lastPresence, msg)) return touch(msg.from, now)
                setDevice((known ?: Device(msg.from, msg.name, msg.form, now)).copy(name = msg.name, form = msg.form, lastSeen = now))
                // A device just arrived: tell it about this one straight away, and learn its clock.
                if (known == null) { sayPresence(); sayState(force = true); ping(msg.from, now) }
            }
            is State -> onState(msg, known, now)
            is Leave -> {
                // A goodbye from before something newer it said (a restart's hello overtaking it) is stale.
                // That hello found this device still known, so nothing went back: the restarted one
                // hasn't heard of it, and a paused one won't say its state again unasked.
                if (msg.seq < latest) {
                    if (known != null) { sayPresence(); sayState(force = true) }
                    return
                }
                leftAt[msg.from] = msg.seq
                devices.remove(msg.from)
                offsets.remove(msg.from)
                pinged.remove(msg.from)
                refresh()
                msg.next?.let(moved)
            }
            is Command -> {
                touch(msg.from, now)
                if (msg.to != myId || !newer(lastControl, msg)) return
                val ok = apply(msg)
                send(Ack(myId, ++seq, now, to = msg.from, id = msg.id, ok = ok, reason = if (ok) null else "unplayable"))
                // A play or pause shows in the player's state a moment later; its own change sends it then.
                // The rest is told once the player has settled (its repeat, for one, arrives a moment after).
                if (msg.action != "play" && msg.action != "pause") scope.launch { delay(SETTLE_MS); localChanged() }
            }
            is Transfer -> {
                touch(msg.from, now)
                // A copy of one taken already, or one older than a command that came after it, isn't taken again.
                if (msg.to != myId || !newer(lastControl, msg)) return
                // Playing, it has gone on for as long as the message took, measured on one clock (the sender's,
                // turned into this one's); the two clocks' difference, seconds at times, is no part of it. Paused, it stays put.
                val pos = msg.pos + if (msg.playing) transit(msg, now) else 0
                val ok = player.load(msg.queue, msg.index, pos, msg.playing)
                send(Ack(myId, ++seq, now, to = msg.from, id = msg.id, ok = ok, reason = if (ok) null else "unplayable"))
                if (ok) { mine = null; mineAt = clock(); localChanged() }
            }
            is Ack -> {
                touch(msg.from, now)
                if (msg.to != myId) return
                val p = pending.remove(msg.id) ?: return
                p.job.cancel()
                refresh()
                p.onAck(msg)
            }
            is Ping -> {
                touch(msg.from, now)
                if (msg.to == myId) send(Pong(myId, ++seq, now, to = msg.from, c = msg.c, h = now))
            }
            is Pong -> {
                touch(msg.from, now)
                if (msg.to == myId) clockHeard(msg, now)
            }
        }
    }

    private fun onState(msg: State, known: Device?, now: Long) {
        if (!newer(lastState, msg)) return touch(msg.from, now)
        var st: State = msg
        // Something pressed here a moment ago: a report from before it landed doesn't undo it on screen.
        guesses[msg.from]?.let { g0 ->
            var g = g0
            if (now >= g.until) g = g.copy(playing = null, shuffle = null, repeat = null)
            if (now >= g.placeUntil) g = g.copy(pos = null, track = null)
            if (g.playing != null && msg.meant != g.playing) return touch(msg.from, now)
            if (g.pos != null) {
                // The place pressed (a seek, a skip): a report elsewhere in the song, or on another one, is from before.
                val off = (g.track != null && msg.track?.id != g.track) || kotlin.math.abs(msg.pos - g.pos) > PLACE_SLACK_MS + (now - g.placeAt)
                if (off) return touch(msg.from, now)
            }
            val confirmed = (g.shuffle == null || msg.shuffle == g.shuffle) && (g.repeat == null || msg.repeat == g.repeat)
            if (confirmed) guesses.remove(msg.from) else {
                st = msg.copy(shuffle = g.shuffle ?: msg.shuffle, repeat = g.repeat ?: msg.repeat)
                guesses[msg.from] = g.copy(playing = null, pos = null, track = null)
            }
        }
        val base = known ?: Device(msg.from, "", "", now)
        // When it was said, on this clock: its position counts on from then.
        val said = now - transit(msg, now)
        // When it took the music, as long before it said so as its own clock says: the two clocks'
        // difference (seconds, at times) doesn't decide which device took it last.
        val since = st.since?.let { said - (st.at - it).coerceAtLeast(0) }
            ?: if (base.state?.track != st.track || (st.meant && base.state?.meant != true)) now else base.since
        val d = base.copy(lastSeen = now, state = st, receivedAt = said, since = since)
        setDevice(d)
        if (known == null) sayPresence()
        yieldTo(d)
    }

    /**
     * Two devices meant to play at once (a pause command or a handover's
     * answer lost on the way): the one that took the music last keeps it, and
     * this one, if it took it earlier, stops.
     */
    private fun yieldTo(d: Device) {
        val st = d.state ?: return
        if (!st.meant || !d.hasSomething || d.since - mineAt < YIELD_MS) return
        val here = player.snapshot()
        if (!meant(here) || !has(here)) return
        Log.i(TAG, "${d.id} took the music after this device; pausing here")
        player.pause()
        scope.launch { delay(SETTLE_MS); localChanged() }
    }

    private fun ping(id: String, now: Long) {
        val before = pinged[id]
        pinged[id] = Pinged(now, (before?.unanswered ?: 0) + 1)
        send(ConnectMsg.Ping(myId, ++seq, now, to = id, c = now))
    }

    private fun clockHeard(p: ConnectMsg.Pong, now: Long) {
        val rtt = now - p.c
        if (rtt < 0 || rtt > MAX_RTT_MS) return
        pinged[p.from]?.let { pinged[p.from] = Pinged(it.at, 0) }
        // The answer was made about halfway through the round trip.
        val sample = Offset(p.h - (p.c + now) / 2, rtt, now)
        val best = offsets[p.from]
        if (best == null || rtt <= best.rtt || now - best.at > OFFSET_KEEP_MS) offsets[p.from] = sample
    }

    private fun apply(c: Command): Boolean {
        when (c.action) {
            "play" -> player.play()
            "pause" -> player.pause()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seekTo(c.pos)
            "volume" -> player.setVolume(c.value)
            "shuffle" -> player.setShuffle(c.value > 0.5f)
            "repeat" -> player.setRepeat(c.mode?.takeIf { it in setOf("off", "all", "one") } ?: return false)
            "playTrack" -> player.playTrack(c.track ?: return false)
            "addToQueue" -> player.addToQueue(c.tracks)
            "jump" -> return player.playQueued(c.pos.toInt(), c.track?.id ?: return false)
            "remove" -> return player.removeQueued(c.pos.toInt(), c.track?.id ?: return false)
            else -> return false
        }
        return true
    }

    // ---- Out -------------------------------------------------------------------------------

    private fun command(
        to: String,
        action: String,
        pos: Long = 0,
        value: Float = 0f,
        track: CTrack? = null,
        tracks: List<CTrack> = emptyList(),
        mode: String? = null,
        quiet: Boolean = false,
        onAck: (Ack) -> Unit = {},
    ) {
        val id = newId()
        send(Command(myId, ++seq, clock(), to = to, id = id, action = action, pos = pos, value = value, track = track, tracks = tracks, mode = mode))
        if (!quiet) expect(id, to, timings.commandMs, onAck = onAck)
    }

    /**
     * Waits for the answer to [id]; says so if none comes in [ms], unless
     * [answered] says the device did it all the same (its answer lost).
     */
    private fun expect(id: String, to: String, ms: Long, answered: () -> Boolean = { false }, onAck: (Ack) -> Unit) {
        val job = scope.launch {
            delay(ms)
            if (pending.remove(id) != null) {
                refresh()
                if (answered()) return@launch onAck(Ack(to, 0, clock(), to = myId, id = id, ok = true))
                notice(NOT_ANSWERING, nameOf(to))
                onAck(Ack(to, 0, clock(), to = myId, id = id, ok = false, reason = "timeout"))
            }
        }
        pending[id] = Pending(to, job, onAck)
        refresh()
    }

    private fun tick() {
        val now = clock()
        val gone = devices.values.filter { now - it.lastSeen > timings.goneMs }.map { it.id }
        if (gone.isNotEmpty()) {
            gone.forEach { devices.remove(it); offsets.remove(it); pinged.remove(it) }
            refresh()
        }
        if (now - lastPresenceSent >= timings.presenceMs) sayPresence()
        if (mine?.playing == true && now - lastStateSent >= timings.stateMs) sayState()
        // Each device's clock, measured again now and then; a few quick tries for one not yet measured
        // (a device too old to answer is left at the slow pace after those).
        for (d in devices.values) {
            val p = pinged[d.id]
            val wait = if (offsets[d.id] == null && (p?.unanswered ?: 0) < PING_TRIES) minOf(timings.presenceMs, PING_RETRY_MS) else timings.presenceMs
            if (p == null || now - p.at >= wait) ping(d.id, now)
        }
    }

    private fun sayPresence() {
        lastPresenceSent = clock()
        send(Presence(myId, ++seq, clock(), name = myName, form = form, active = view.value.activeId == myId))
    }

    private fun sayState(force: Boolean = false) {
        val s = player.snapshot() ?: return
        mine = s
        if (s.track == null && !s.private && !force) return
        lastStateSent = clock()
        send(State(myId, ++seq, clock(), track = s.track, private = s.private, playing = s.playing, pos = s.pos, queue = s.queue.take(ConnectMsg.MAX_QUEUE), volume = s.volume, shuffle = s.shuffle, repeat = s.repeat, since = mineAt.takeIf { it > 0 }, quality = s.quality, wants = meant(s)))
    }

    private fun send(m: ConnectMsg, to: ConnectGroup = group) {
        outbox.trySend(m to to)
    }

    /** A device heard in a former group: told, at most once a minute, to come over to this one. */
    private fun forward(msg: ConnectMsg, old: ConnectGroup) {
        if (msg.from == myId || msg is Leave) return
        val key = "${old.tag}/${msg.from}"
        val now = clock()
        if (now - (forwarded[key] ?: 0L) < FORWARD_MS) return
        forwarded[key] = now
        send(Leave(myId, ++seq, now, next = group.encoded), old)
    }

    // ---- The view --------------------------------------------------------------------------

    private fun touch(id: String, now: Long) {
        devices[id]?.let { devices[id] = it.copy(lastSeen = now) }
    }

    private fun setDevice(d: Device) {
        devices[d.id] = d
        refresh()
    }

    private fun refresh() {
        val others = devices.values.toList()
        view.value = view.value.copy(devices = others, activeId = active(others), pending = pending.keys.toSet())
    }

    /**
     * Whichever device is meant to play (the latest to take the music, if
     * several), stalled to load or not; else the one that took it last. A
     * stall isn't a pause: judged by moving, a device loading a song it was
     * just handed lost the music to the one that handed it over, and the
     * buttons pressed meanwhile went there.
     */
    private fun active(others: List<Device>): String? {
        val m = mine
        val meHas = has(m)
        val meMeant = meHas && meant(m)
        val playing = others.filter { it.hasSomething && it.state!!.meant }.maxByOrNull { it.since }
        if (meMeant && (playing == null || mineAt >= playing.since)) return myId
        if (playing != null) return playing.id
        val newest = others.filter { it.hasSomething }.maxByOrNull { it.since }
        return when {
            meHas && (newest == null || mineAt >= newest.since) -> myId
            newest != null -> newest.id
            else -> null
        }
    }

    private fun nameOf(id: String) = if (id == myId) myName else devices[id]?.name?.ifBlank { null } ?: "The other device"

    private fun newId() = UUID.randomUUID().toString().take(12)

    companion object {
        /** How often a device left in a former group is told again where the others went. */
        const val FORWARD_MS = 60_000L
        /** How long a command's change takes to show in the player's state. */
        const val SETTLE_MS = 150L
        const val KIND = 21421
        /** How long a play or pause pressed here outlasts reports from before it landed. */
        private const val GUESS_MS = 1_500L
        /** The same for a place pressed (a seek, a skip): the command goes, the player settles, its report comes back. */
        private const val PLACE_MS = 3_000L
        /** How far off a pressed place a report can be and still be after it. */
        private const val PLACE_SLACK_MS = 2_000L
        /** A message's trip is never counted longer than this: one held up longer is late, not news. */
        private const val MAX_TRANSIT_MS = 5_000L
        private const val MAX_RTT_MS = 20_000L
        /** A clock measured on a slower trip replaces the best one once that's this old. */
        private const val OFFSET_KEEP_MS = 10 * 60_000L
        private const val PING_TRIES = 3
        private const val PING_RETRY_MS = 2_000L
        /** How much later another device has to have taken the music for this one to stop for it. */
        private const val YIELD_MS = 1_500L
        /** A handover's target taking the music this long before it was sent still counts as taking it. */
        private const val TOOK_SLACK_MS = 2_000L
        const val NOT_ANSWERING = "{0} isn't answering"
        const val CANT_PLAY = "Can't play this on {0}"
        const val NEEDS_TAP = "Click in {0} first, then try again"
        private const val TAG = "Connect"
    }
}
