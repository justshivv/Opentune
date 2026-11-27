package com.opentune.data.connect

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.opentune.data.model.Song
import com.opentune.data.together.RelayPool
import com.opentune.data.together.Together
import com.opentune.i18n.Tr
import com.opentune.playback.PlayerConnection
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Connect: this person's devices see what the others play, control it, and
 * hand playback between them, like Spotify Connect. It runs over the same
 * public Nostr relays as Listen Together, in a group of their own devices
 * that shares one secret (see [ConnectGroup]).
 *
 * The phone is in the group while the app's player is attached: open, or
 * in the background with the app still running. Closing the app leaves it.
 */
object Connect {
    private const val FORM = "phone"

    private val main by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var prefs: SharedPreferences? = null
    private var connection: PlayerConnection? = null
    private var session: ConnectSession? = null
    private var watching: Job? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _group = MutableStateFlow<ConnectGroup?>(null)
    val group: StateFlow<ConnectGroup?> = _group.asStateFlow()

    private val _accountSync = MutableStateFlow<Boolean?>(null)
    /**
     * Whether Connect may keep its key in the YouTube account (a private
     * playlist): null until the person has been asked, once, after signing
     * in. Nothing is read or written there before a yes.
     */
    val accountSync: StateFlow<Boolean?> = _accountSync.asStateFlow()

    private val _name = MutableStateFlow("")
    val deviceName: StateFlow<String> = _name.asStateFlow()

    private val _view = MutableStateFlow<ConnectView?>(null)
    /** The group as this device sees it; null while Connect isn't running. */
    val view: StateFlow<ConnectView?> = _view.asStateFlow()

    /** The other device whose playback this one shows and controls, if any. */
    val controlling: StateFlow<Device?> by lazy {
        _view.map { it?.controlling }.let { flow ->
            val out = MutableStateFlow<Device?>(null)
            main.launch { flow.collect { out.value = it } }
            out.asStateFlow()
        }
    }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short messages for the UI to show, already translated. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    lateinit var myId: String
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("connect", Context.MODE_PRIVATE)
        prefs = p
        myId = p.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(12).also { p.edit { putString("device_id", it) } }
        _enabled.value = p.getBoolean("enabled", true)
        // Asked for, never assumed ("account_sync" was a default-on switch before).
        _accountSync.value = if (p.contains("account_consent")) p.getBoolean("account_consent", false) else null
        _group.value = ConnectGroup.parse(p.getString("secret", null))
        _name.value = p.getString("name", null) ?: defaultName(context)
    }

    private fun defaultName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // ---- The player ------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    fun attach(player: PlayerConnection) {
        connection = player
        watching?.cancel()
        // The player's changes go out a moment after they settle.
        watching = main.launch {
            merge(
                merge(
                    combine(player.currentSong, player.isPlaying, player.queue, player.upNext, player.volume) { _, _, _, _, _ -> Unit },
                    // A stall starting or ending too: other devices' clocks for this one stop and start with it.
                    combine(player.shuffleMode, player.repeatMode, player.audioFormat, com.opentune.data.NerdStats.playbackSource, player.isSounding) { _, _, _, _, _ -> Unit },
                    // The song's length once the player knows it (see snapshot), for others' seek bars.
                    player.durationOf.map { (id, ms) -> id to (ms > 0) }.distinctUntilChanged().map { },
                ).debounce(300),
                // A seek goes out at once: held back with the rest, the other devices ran on from the old
                // place meanwhile, and a stall or a quality label changing kept putting it off.
                player.seeks,
            ).collect { session?.localChanged() }
        }
        restart()
        // Once signed in (at start, or later), the account's group is looked for.
        main.launch { com.opentune.data.account.AccountStore.signedIn.collect { if (it) syncAccount() } }
    }

    fun detach() {
        watching?.cancel()
        watching = null
        stopSession()
        connection = null
    }

    // ---- Settings --------------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit { putBoolean("enabled", on) }
        restart()
    }

    fun setDeviceName(name: String) {
        val clean = name.trim().take(40).ifBlank { return }
        _name.value = clean
        prefs?.edit { putString("name", clean) }
        session?.rename(clean)
    }

    fun setAccountSync(on: Boolean) {
        _accountSync.value = on
        prefs?.edit { putBoolean("account_consent", on) }
        if (on) syncAccount()
    }

    /**
     * With account sync on and signed in: joins the group kept in the account,
     * or keeps this device's group there when the account has none yet.
     * [push] puts this device's group there even when the account has one
     * (a new secret after "Disconnect all").
     */
    fun syncAccount(push: Boolean = false) {
        if (_accountSync.value != true || !com.opentune.data.account.AccountStore.signedIn.value) return
        main.launch {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val kept = if (push) null else ConnectAccountSync.read()
                    if (kept == null) ConnectAccountSync.write(ensureGroupOff())
                    kept
                }
            }.onSuccess { kept -> if (kept != null && kept != _group.value) join(kept) }
                .onFailure { com.opentune.data.DebugLog.w("Connect", "account sync: ${it.message}") }
        }
    }

    /** [ensureGroup] from a background thread: the group is made, its restart left to the main one. */
    private suspend fun ensureGroupOff(): ConnectGroup =
        _group.value ?: kotlinx.coroutines.withContext(Dispatchers.Main) { ensureGroup() }

    /** The group, made now if this device has none yet. */
    fun ensureGroup(): ConnectGroup = _group.value ?: ConnectGroup.generate().also { join(it) }

    /**
     * Makes this device one of [group]'s. With [bring], the devices of its
     * old group come along: pairing only ever adds devices, it never leaves
     * some behind.
     */
    fun join(group: ConnectGroup, bring: Boolean = true, remember: Boolean = true) {
        if (group == _group.value) return
        val old = _group.value
        _group.value = group
        // The old group is listened to for a while longer, for its devices that were off just now.
        val former = if (remember) (listOfNotNull(old?.encoded) + formerSecrets()).filter { it != group.encoded }.distinct().take(MAX_FORMER) else emptyList()
        prefs?.edit { putString("secret", group.encoded); putString("former", former.joinToString(",")) }
        if (bring) session?.let { took = it.tookAt; it.stop(next = group.encoded); session = null }
        restart()
    }

    private const val MAX_FORMER = 3

    private fun formerSecrets(): List<String> = prefs?.getString("former", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** Pairing with another device's code: its group, with this one's devices brought along and kept in the account. */
    fun pair(group: ConnectGroup) {
        join(group)
        syncAccount(push = true)
    }

    /** A new secret: every other device falls out until it's added again. */
    fun disconnectAll(): ConnectGroup {
        val fresh = ConnectGroup.generate()
        // Nobody is told where it went: the devices left behind stay out.
        join(fresh, bring = false, remember = false)
        syncAccount(push = true)
        return fresh
    }

    // ---- Acting on the device playing --------------------------------------------------------

    /**
     * The player's play/pause, next, previous and seek, sent to the other
     * device when it's the one playing. True when it went there, so the
     * caller does nothing here.
     */
    fun togglePlayPause(): Boolean {
        val target = controlling.value ?: return false
        // By what it's meant to do: one stalled to load is playing, and Pause pauses it.
        return session?.intercept(if (target.state?.meant == true) "pause" else "play") ?: false
    }
    fun skipNext(): Boolean = session?.intercept("next") ?: false
    /** Plays entry [at] ([track]) of the Up Next on the device playing elsewhere. */
    fun playQueued(at: Int, track: CTrack): Boolean {
        val target = controlling.value ?: return false
        session?.jumpOn(target.id, at, track) ?: return false
        return true
    }
    /** Takes entry [at] ([track]) out of the Up Next on the device playing elsewhere. */
    fun removeQueued(at: Int, track: CTrack): Boolean {
        val target = controlling.value ?: return false
        session?.removeOn(target.id, at, track) ?: return false
        return true
    }
    fun skipPrevious(): Boolean = session?.intercept("previous") ?: false
    fun seekTo(positionMs: Long): Boolean = session?.intercept("seek", pos = positionMs) ?: false
    fun setVolume(value: Float): Boolean = session?.intercept("volume", value = value) ?: false
    /** Shuffle on or off on the device playing elsewhere. */
    fun toggleShuffle(): Boolean {
        val target = controlling.value ?: return false
        return session?.intercept("shuffle", value = if (target.state?.shuffle == true) 0f else 1f) ?: false
    }
    /** Off, all, one, as this player's own repeat button goes, on the device playing elsewhere. */
    fun cycleRepeat(): Boolean {
        val target = controlling.value ?: return false
        val next = when (target.state?.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
        return session?.intercept("repeat", mode = next) ?: false
    }

    /** How the music sounds here, in the player's words ("Lossless · 16-bit/44.1 kHz"); set by the app. */
    @Volatile var qualityLabel: () -> String? = { null }

    /** Moves playback to [deviceId]; this device's own id brings it here. */
    fun transferTo(deviceId: String) = session?.transferTo(deviceId)

    // ---- Running ---------------------------------------------------------------------------

    private fun restart() {
        stopSession()
        val group = _group.value ?: return
        val player = connection ?: return
        if (!_enabled.value) return
        val former = formerSecrets().mapNotNull(ConnectGroup::parse).filter { it != group }
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ConnectSession.KIND))))
            put("#t", JsonArray((listOf(group) + former).map { JsonPrimitive(it.tag) }))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val view = MutableStateFlow(ConnectView(myId, _name.value))
        main.launch { view.collect { _view.value = it } }.also { viewJob = it }
        session = ConnectSession(
            group, myId, _name.value, FORM, ConnectionConnectPlayer(player), RelayPool(Together.RELAYS, filter), view,
            { template, name -> _notices.tryEmit(Tr.f(template, name)) }, main,
            moved = { secret -> ConnectGroup.parse(secret)?.let { main.launch { join(it, bring = false) } } },
            former = former,
            took = took,
        ).also { it.start() }
    }

    private var viewJob: Job? = null

    /**
     * When this phone last took the music, kept across sessions: Connect
     * starting over (the player screen made again, a group joined, the
     * switch flipped) isn't taking it. Counted as taking it, a paused phone
     * became the device shown everywhere, and Play pressed elsewhere played here.
     */
    private var took = 0L

    private fun stopSession() {
        session?.let { took = it.tookAt }
        session?.stop()
        session = null
        viewJob?.cancel()
        viewJob = null
        _view.value = null
    }
}

/** [ms] as a listing writes a length: "3:45", or "1:02:03" from an hour. */
internal fun lengthText(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The app's player, as Connect sees it. */
internal class ConnectionConnectPlayer(private val c: PlayerConnection) : ConnectPlayer {
    override fun snapshot(): Snapshot? {
        val song = c.currentSong.value ?: return null
        val queue = c.queue.value
        // Many songs come without a length in their listing; the player's own, once it knows it,
        // goes in its place, or other devices showed no seek bar and a click on it went to 0:00.
        val track = CTrack.of(song)?.let { t ->
            val (id, ms) = c.durationOf.value
            if (t.length.isNullOrBlank() && id == song.videoId && ms > 0) t.copy(length = lengthText(ms)) else t
        }
        return Snapshot(
            track = track,
            private = track == null,
            // Moving, not just meant to: other devices run this one's clock on from its last report
            // while it says it's playing, so a stall said as playing ran their timestamp ahead of it.
            // Another player carrying the song through a quality switch counts as moving.
            playing = c.isPlaying.value && (c.isSounding.value || com.opentune.playback.MixState.showing.value != null),
            wants = c.isPlaying.value,
            pos = c.currentPositionMs(),
            queue = c.upNext.value.mapNotNull { queue.getOrNull(it)?.let(CTrack::of) },
            volume = c.volume.value,
            // The app's shuffle reorders the queue and leaves the player's own flag off.
            shuffle = c.shuffleMode.value != com.opentune.playback.ShuffleMode.OFF,
            repeat = when (c.repeatMode.value) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "one"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            },
            quality = runCatching { Connect.qualityLabel() }.getOrNull(),
        )
    }
    override fun setShuffle(on: Boolean) {
        val mode = c.shuffleMode.value
        when {
            on && mode == com.opentune.playback.ShuffleMode.OFF -> c.toggleShuffle()
            // Smart shuffle goes off its own way: its next step is off.
            !on && mode == com.opentune.playback.ShuffleMode.SMART -> c.cycleShuffle()
            !on && mode == com.opentune.playback.ShuffleMode.ON -> c.toggleShuffle()
        }
    }
    override fun setRepeat(mode: String) {
        val want = when (mode) {
            "one" -> androidx.media3.common.Player.REPEAT_MODE_ONE
            "all" -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        c.setRepeatMode(want)
    }
    override fun play() = c.play()
    override fun pause() = c.pause()
    override fun next() = c.skipNext()
    override fun previous() = c.skipPrevious()
    override fun seekTo(ms: Long) = c.seekTo(ms)
    override fun setVolume(v: Float) = c.setVolume(v)
    override fun playTrack(t: CTrack) {
        c.playNext(t.toSong())
        c.skipNext()
    }
    override fun addToQueue(ts: List<CTrack>) = ts.forEach { c.addToQueue(it.toSong()) }

    /** The queue row of entry [at] in [Snapshot.queue] (the shareable songs after this one), checked against [id]. */
    private fun row(at: Int, id: String): Int? {
        val queue = c.queue.value
        val rows = c.upNext.value.filter { queue.getOrNull(it)?.let(CTrack::of) != null }
        return rows.getOrNull(at)?.takeIf { queue[it].videoId == id } ?: rows.firstOrNull { queue[it].videoId == id }
    }
    override fun playQueued(at: Int, id: String): Boolean {
        c.playQueueItem(row(at, id) ?: return false)
        return true
    }
    override fun removeQueued(at: Int, id: String): Boolean {
        c.removeQueueItem(row(at, id) ?: return false)
        return true
    }
    override fun moveQueued(from: Int, to: Int, id: String): Boolean {
        val queue = c.queue.value
        val rows = c.upNext.value.filter { queue.getOrNull(it)?.let(CTrack::of) != null }
        val row = row(from, id) ?: return false
        val target = rows.getOrNull(to) ?: return false
        if (row != target) c.moveQueueItem(row, target)
        return true
    }
    override fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean {
        if (queue.isEmpty() || index !in queue.indices) return false
        // Not yet joined to the player service (the app just opened): it would go nowhere, and the
        // device handing it over, told it was taken, would stop with nothing playing anywhere.
        if (!c.connected.value) return false
        val songs: List<Song> = queue.map { it.toSong() }
        // That device's Up Next, as it was: songs left queued by hand here don't push in after its song.
        c.playAll(songs, index, shuffle = false, source = null, startPositionMs = pos, fromList = false, keepQueued = false)
        if (!playing) c.pause()
        return true
    }
}
