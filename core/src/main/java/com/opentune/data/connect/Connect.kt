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

    private val _accountSync = MutableStateFlow(false)
    val accountSync: StateFlow<Boolean> = _accountSync.asStateFlow()

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
        _accountSync.value = p.getBoolean("account_sync", false)
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
                combine(player.currentSong, player.isPlaying, player.queue, player.upNext, player.volume) { _, _, _, _, _ -> Unit },
                player.seeks,
            ).debounce(300).collect { session?.localChanged() }
        }
        restart()
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
        prefs?.edit { putBoolean("account_sync", on) }
    }

    /** The group, made now if this device has none yet. */
    fun ensureGroup(): ConnectGroup = _group.value ?: ConnectGroup.generate().also { join(it) }

    /** Makes this device one of [group]'s. */
    fun join(group: ConnectGroup) {
        if (group == _group.value) return
        _group.value = group
        prefs?.edit { putString("secret", group.encoded) }
        restart()
    }

    /** A new secret: every other device falls out until it's added again. */
    fun disconnectAll(): ConnectGroup {
        val fresh = ConnectGroup.generate()
        _group.value = null
        join(fresh)
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
        return session?.intercept(if (target.state?.playing == true) "pause" else "play") ?: false
    }
    fun skipNext(): Boolean = session?.intercept("next") ?: false
    fun skipPrevious(): Boolean = session?.intercept("previous") ?: false
    fun seekTo(positionMs: Long): Boolean = session?.intercept("seek", pos = positionMs) ?: false
    fun setVolume(value: Float): Boolean = session?.intercept("volume", value = value) ?: false

    /** Moves playback to [deviceId]; this device's own id brings it here. */
    fun transferTo(deviceId: String) = session?.transferTo(deviceId)

    // ---- Running ---------------------------------------------------------------------------

    private fun restart() {
        stopSession()
        val group = _group.value ?: return
        val player = connection ?: return
        if (!_enabled.value) return
        val filter = buildJsonObject {
            put("kinds", JsonArray(listOf(JsonPrimitive(ConnectSession.KIND))))
            put("#t", JsonArray(listOf(JsonPrimitive(group.tag))))
            put("since", System.currentTimeMillis() / 1000 - 10)
        }
        val view = MutableStateFlow(ConnectView(myId, _name.value))
        main.launch { view.collect { _view.value = it } }.also { viewJob = it }
        session = ConnectSession(
            group, myId, _name.value, FORM, ConnectionConnectPlayer(player), RelayPool(Together.RELAYS, filter), view,
            { template, name -> _notices.tryEmit(Tr.f(template, name)) }, main,
        ).also { it.start() }
    }

    private var viewJob: Job? = null

    private fun stopSession() {
        session?.stop()
        session = null
        viewJob?.cancel()
        viewJob = null
        _view.value = null
    }
}

/** The app's player, as Connect sees it. */
internal class ConnectionConnectPlayer(private val c: PlayerConnection) : ConnectPlayer {
    override fun snapshot(): Snapshot? {
        val song = c.currentSong.value ?: return null
        val queue = c.queue.value
        val track = CTrack.of(song)
        return Snapshot(
            track = track,
            private = track == null,
            playing = c.isPlaying.value,
            pos = c.currentPositionMs(),
            queue = c.upNext.value.mapNotNull { queue.getOrNull(it)?.let(CTrack::of) },
            volume = c.volume.value,
            shuffle = c.shuffleEnabled.value,
            repeat = when (c.repeatMode.value) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "one"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            },
        )
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
    override fun load(queue: List<CTrack>, index: Int, pos: Long, playing: Boolean): Boolean {
        if (queue.isEmpty() || index !in queue.indices) return false
        val songs: List<Song> = queue.map { it.toSong() }
        c.playAll(songs, index, shuffle = false, source = null, startPositionMs = pos, fromList = false)
        if (!playing) c.pause()
        return true
    }
}
