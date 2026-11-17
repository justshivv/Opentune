package com.opentune.data.connect

import com.opentune.data.DebugLog as Log
import com.opentune.data.connect.ConnectMsg.Ack
import com.opentune.data.connect.ConnectMsg.Command
import com.opentune.data.connect.ConnectMsg.Leave
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
    /** Loads [queue] from [index] at [pos] ms; false when none of it can play here. */
    fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean
}

data class Snapshot(
    val track: CTrack?,
    /** Something only this device can play (its own files, a private server). */
    val private: Boolean,
    val playing: Boolean,
    val pos: Long,
    /** What comes after [track]. */
    val queue: List<CTrack>,
    val volume: Float,
    val shuffle: Boolean,
    val repeat: String,
    /** How it sounds here, in this device's words; null when it can't say. */
    val quality: String? = null,
)

/**
 * Another of the person's devices, as last heard. [receivedAt] is this
 * device's clock when its [state] came; [since] when it took the music (see
 * [State.since]).
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
    /** Where its song is now, counting on from its last state while it plays. */
    fun positionAt(now: Long): Long = state?.let { if (it.playing) it.pos + (now - receivedAt) else it.pos } ?: 0

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
) {
    private val key = Schnorr.newPrivateKey()
    private val myPub = Schnorr.publicKey(key).toHex()
    private val outbox = Channel<ConnectMsg>(Channel.UNLIMITED)
    private val jobs = mutableListOf<Job>()
    private var seq = 0L
    private val lastSeq = HashMap<String, Long>()
    private val devices = LinkedHashMap<String, Device>()

    private var mine: Snapshot? = null
    private var mineAt = 0L
    private var lastStateSent = 0L
    private var lastPresenceSent = 0L

    private class Pending(val to: String, val job: Job, val onAck: (Ack) -> Unit)
    private val pending = HashMap<String, Pending>()
    /** Play or pause pressed here for a device: until when, and which it was. */
    private val guesses = HashMap<String, Pair<Long, Boolean>>()

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
                val msg = withContext(Dispatchers.Default) { group.open(event.content)?.let(ConnectMsg::decode) } ?: return@collect
                handle(msg)
            }
        }
        // Not in [jobs]: it has to outlive stop() long enough to send the goodbye.
        scope.launch(Dispatchers.Default) {
            for (m in outbox) {
                val event = NostrEvent.signed(key, KIND, listOf(listOf("t", group.tag)), ConnectMsg.fit(m, group))
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
        if (mine?.track != null || mine?.private == true) mineAt = clock()
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
        val started = now.playing && before?.playing != true
        // Taking the music: a new song, or starting it. Pausing leaves it where it was taken.
        if (now.track != before?.track || now.private != before?.private || (now.playing && before?.playing != true)) mineAt = clock()
        if (started) {
            for (d in devices.values) if (d.state?.playing == true) command(d.id, "pause", quiet = true)
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
            val guess = when (action) {
                "play" -> shown.copy(playing = true, pos = target.positionAt(clock()))
                "pause" -> shown.copy(playing = false, pos = target.positionAt(clock()))
                "seek" -> shown.copy(pos = pos)
                "volume" -> shown.copy(volume = value)
                "shuffle" -> shown.copy(shuffle = value > 0.5f)
                "repeat" -> shown.copy(repeat = mode ?: shown.repeat)
                else -> null
            }
            if (guess != null) setDevice(target.copy(state = guess, receivedAt = clock()))
            if (guess != null && (action == "play" || action == "pause")) guesses[target.id] = clock() + GUESS_MS to guess.playing
        }
        command(target.id, action, pos = pos, value = value, mode = mode) { ack ->
            if (!ack.ok && shown != null) { guesses.remove(target.id); setDevice(target.copy(state = shown)) }
        }
        return true
    }

    fun playTrackOn(deviceId: String, track: CTrack) = command(deviceId, "playTrack", track = track)
    fun addToQueueOn(deviceId: String, tracks: List<CTrack>) = command(deviceId, "addToQueue", tracks = tracks)

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
            send(Transfer(myId, ++seq, clock(), to = deviceId, id = id, queue = queue, index = 0, pos = snap.pos, playing = snap.playing))
            expect(id, deviceId, timings.transferMs) { ack ->
                if (ack.ok) player.pause() else refused(ack, deviceId)
            }
            return
        }
        val source = devices[activeId] ?: return
        val state = source.state ?: return
        val track = state.track ?: run { notice(CANT_PLAY, nameOf(deviceId)); return }
        val queue = listOf(track) + state.queue
        val pos = source.positionAt(clock())
        if (deviceId == myId) {
            if (player.load(queue, 0, pos, state.playing)) {
                command(source.id, "pause", quiet = true)
                mine = null
                mineAt = clock()
                localChanged()
            } else notice(CANT_PLAY, myName)
            return
        }
        val id = newId()
        send(Transfer(myId, ++seq, clock(), to = deviceId, id = id, queue = queue, index = 0, pos = pos, playing = state.playing))
        expect(id, deviceId, timings.transferMs) { ack ->
            if (ack.ok) command(source.id, "pause", quiet = true) else refused(ack, deviceId)
        }
    }

    /** Why [deviceId] didn't take the music, for the person. */
    private fun refused(ack: Ack, deviceId: String) = notice(if (ack.reason == "needs-tap") NEEDS_TAP else CANT_PLAY, nameOf(deviceId))

    // ---- In --------------------------------------------------------------------------------

    internal fun handle(msg: ConnectMsg) {
        if (msg.from == myId) return
        val last = lastSeq[msg.from]
        if (last != null && msg.seq <= last) return
        lastSeq[msg.from] = msg.seq
        val now = clock()
        val known = devices[msg.from]
        when (msg) {
            is Presence -> {
                setDevice((known ?: Device(msg.from, msg.name, msg.form, now)).copy(name = msg.name, form = msg.form, lastSeen = now))
                // A device just arrived: tell it about this one straight away.
                if (known == null) { sayPresence(); sayState(force = true) }
            }
            is State -> {
                // A play or pause pressed here a moment ago: a report from before it landed doesn't undo it on screen.
                guesses[msg.from]?.let { (until, playing) ->
                    if (now < until && msg.playing != playing) { touch(msg.from, now); return }
                    guesses.remove(msg.from)
                }
                val base = known ?: Device(msg.from, "", "", now)
                val since = msg.since ?: if (base.state?.track != msg.track || (msg.playing && base.state?.playing != true)) now else base.since
                setDevice(base.copy(lastSeen = now, state = msg, receivedAt = now, since = since))
                if (known == null) sayPresence()
            }
            is Leave -> {
                devices.remove(msg.from)
                refresh()
                msg.next?.let(moved)
            }
            is Command -> {
                touch(msg.from, now)
                if (msg.to != myId) return
                val ok = apply(msg)
                send(Ack(myId, ++seq, now, to = msg.from, id = msg.id, ok = ok, reason = if (ok) null else "unplayable"))
                // A play or pause shows in the player's state a moment later; its own change sends it then.
                if (msg.action != "play" && msg.action != "pause") localChanged()
            }
            is Transfer -> {
                touch(msg.from, now)
                if (msg.to != myId) return
                val ok = player.load(msg.queue, msg.index, msg.pos + (now - msg.at).coerceIn(0, 10_000), msg.playing)
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
            else -> touch(msg.from, now)
        }
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
        if (!quiet) expect(id, to, timings.commandMs, onAck)
    }

    /** Waits for the answer to [id]; says so if none comes in [ms]. */
    private fun expect(id: String, to: String, ms: Long, onAck: (Ack) -> Unit) {
        val job = scope.launch {
            delay(ms)
            if (pending.remove(id) != null) {
                refresh()
                notice(NOT_ANSWERING, nameOf(to))
                onAck(Ack(to, 0, clock(), to = myId, id = id, ok = false, reason = "timeout"))
            }
        }
        pending[id] = Pending(to, job, onAck)
        refresh()
    }

    private fun tick() {
        val now = clock()
        if (devices.values.removeAll { now - it.lastSeen > timings.goneMs }) refresh()
        if (now - lastPresenceSent >= timings.presenceMs) sayPresence()
        if (mine?.playing == true && now - lastStateSent >= timings.stateMs) sayState()
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
        send(State(myId, ++seq, clock(), track = s.track, private = s.private, playing = s.playing, pos = s.pos, queue = s.queue.take(ConnectMsg.MAX_QUEUE), volume = s.volume, shuffle = s.shuffle, repeat = s.repeat, since = mineAt.takeIf { it > 0 }, quality = s.quality))
    }

    private fun send(m: ConnectMsg) {
        outbox.trySend(m)
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

    /** Whichever device plays (the latest to start, if several); else the one that last had something loaded. */
    private fun active(others: List<Device>): String? {
        val m = mine
        val meHas = m != null && (m.track != null || m.private)
        if (meHas && m!!.playing) return myId
        val playing = others.filter { it.hasSomething && it.state!!.playing }.maxByOrNull { it.since }
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
        const val KIND = 21421
        /** How long a play or pause pressed here outlasts reports from before it landed. */
        private const val GUESS_MS = 1_500L
        const val NOT_ANSWERING = "{0} isn't answering"
        const val CANT_PLAY = "Can't play this on {0}"
        const val NEEDS_TAP = "Click in {0} first, then try again"
        private const val TAG = "Connect"
    }
}
