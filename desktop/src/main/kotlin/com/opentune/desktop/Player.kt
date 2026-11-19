package com.opentune.desktop

import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.isYouTubeId
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * The queue and the song playing. A song starts from YouTube (or your own
 * server), decoded by FFmpeg onto the [AudioBus]; meanwhile the audio sources
 * are asked for something better, as on the phone: a verified FLAC from an
 * add-on, or JioSaavn's 320 kbps AAC. Whichever answers takes over at the same
 * moment in the song, and the next song's sources are asked ahead, so it can
 * start in its best quality.
 *
 * The next song decodes ahead into a voice of its own and starts on the sample
 * after this one's last, or fades in over its end when a crossfade is set
 * (Settings → Playback). The phone's speed, balance and equalizer settings
 * apply to everything on the bus. With bit-perfect lossless on, an add-on's
 * FLAC goes straight to the device at its own format instead, untouched.
 *
 * When the queue runs out it carries on with YouTube Music's up-next, when
 * autoplay is on.
 */
object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** When the sleep timer stops playback: at a time, or when the song ends. */
    sealed interface Sleep {
        data class At(val epochMs: Long, val minutes: Int) : Sleep
        data object EndOfSong : Sleep
    }

    /** What happens at the end: stop (or autoplay on), go round the queue again, or the song again. */
    enum class Repeat { OFF, ALL, ONE; fun next() = entries[(ordinal + 1) % entries.size] }

    /** What the song is playing from and in what form, shown under it. */
    data class Quality(val source: String, val detail: String, val lossless: Boolean = false)

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _volume = MutableStateFlow(Library.volume())
    val volume: StateFlow<Double> = _volume.asStateFlow()
    private val _quality = MutableStateFlow<Quality?>(null)
    val quality: StateFlow<Quality?> = _quality.asStateFlow()

    /** The song's id and its exact length once the player knows it, for lyrics to match on. */
    private val _durationOf = MutableStateFlow("" to 0L)
    val durationOf: StateFlow<Pair<String, Long>> = _durationOf.asStateFlow()

    val current: Song? get() = _queue.value.getOrNull(_index.value)

    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()
    private val _shuffled = MutableStateFlow(false)
    val shuffled: StateFlow<Boolean> = _shuffled.asStateFlow()
    /** The queue's order before shuffling, to go back to. */
    private var unshuffled: List<Song>? = null

    fun cycleRepeat() { _repeat.value = _repeat.value.next() }

    /** Shuffles what's after the song playing, or puts the queue back in its order. */
    fun toggleShuffle() {
        val q = _queue.value
        val now = current
        val original = unshuffled
        if (_shuffled.value && original != null) {
            _queue.value = original
            _index.value = now?.let { s -> original.indexOfFirst { it.videoId == s.videoId } }?.takeIf { it >= 0 } ?: 0
            unshuffled = null
            _shuffled.value = false
        } else if (q.isNotEmpty()) {
            unshuffled = q
            val at = _index.value.coerceIn(q.indices)
            _queue.value = listOf(q[at]) + q.filterIndexed { i, _ -> i != at }.shuffled()
            _index.value = 0
            _shuffled.value = true
        }
    }

    private val _sleep = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null
    /** 1 normally; less while the sleep timer winds down. */
    @Volatile private var fade = 1.0

    /** Guards the engines and which song they belong to; callbacks come from several threads. */
    private val gate = Any()
    private var engine: Engine? = null
    /** A better source taking over, until its first sound. */
    private var incoming: Engine? = null
    /** Counts songs opened, so an engine left over from an earlier one is ignored. */
    private var generation = 0
    /** Whether the listener wants sound: true from play until pause. */
    @Volatile private var wanted = false
    private var upgradeJob: Job? = null
    private var aheadJob: Job? = null

    init {
        // A change in Settings reaches the song already playing, and the one coming next.
        scope.launch {
            combine(AppSettings.sound, AppSettings.equalizer) { _, _ -> }.collect {
                synchronized(gate) { listOfNotNull(engine, incoming, prepared?.engine) }.forEach { (it as? FfmpegEngine)?.refreshEffects() }
            }
        }
        // Loudness turned on or off, or its level changed.
        scope.launch {
            AppSettings.playback.collect {
                val e = synchronized(gate) { engine } as? FfmpegEngine ?: return@collect
                val id = current?.videoId ?: return@collect
                if (_quality.value?.source == "YouTube") e.voice.gain = loudnessGain(id)
            }
        }
        // The next song made ready near the end, and the crossfade into it.
        scope.launch {
            while (true) {
                delay(250)
                runCatching { tick() }.onFailure { System.err.println("Player: $it") }
            }
        }
        // A device unplugged: the bus opened another, maybe at another rate; the song starts again there.
        AudioBus.onDeviceChanged = { restartOutput() }
        applyVolume()
    }

    fun playAll(songs: List<Song>, start: Int, atMs: Long = 0) {
        if (songs.isEmpty()) return
        unshuffled = null
        _shuffled.value = false
        _queue.value = songs
        open(start.coerceIn(songs.indices), atMs)
    }

    /** Whether the listener wants sound (playing, or about to once it loads). */
    private val _wanted = MutableStateFlow(false)
    val wantsPlay: StateFlow<Boolean> = _wanted.asStateFlow()

    private val _seeks = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Fires when the listener moves the position by hand, not when a room keeps in step. */
    val seeks: kotlinx.coroutines.flow.SharedFlow<Unit> = _seeks

    /**
     * Listen together, as a guest following a room: the controls go to the host instead of
     * acting here. Returns true when the room took the control.
     */
    @Volatile internal var intercept: ((com.opentune.data.together.Together.Control) -> Boolean)? = null

    fun play() { if (!wanted) toggle() }
    fun pause() { if (wanted) toggle() }

    // ---- Editing the queue -----------------------------------------------------------
    // The song playing never changes here; only what comes before and after it.

    /** [songs] right after the song playing; with nothing playing, they start now. */
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val q = _queue.value
        if (current == null) return playAll(songs, 0)
        val at = _index.value + 1
        _queue.value = q.take(at) + songs + q.drop(at)
        unshuffled = unshuffled?.let { it + songs }
        lookAheadNext()
    }

    /** [songs] at the end of the queue; with nothing playing, they start now. */
    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (current == null) return playAll(songs, 0)
        _queue.value = _queue.value + songs
        unshuffled = unshuffled?.let { it + songs }
        if (_queue.value.size - songs.size == _index.value + 1) lookAheadNext()
    }

    /** Takes the song at [i] out of the queue; the song playing stays. */
    fun removeAt(i: Int) {
        val q = _queue.value
        if (i !in q.indices || i == _index.value) return
        val gone = q[i]
        _queue.value = q.filterIndexed { k, _ -> k != i }
        if (i < _index.value) _index.value -= 1
        unshuffled = unshuffled?.let { list -> list.indexOfFirst { it.videoId == gone.videoId }.takeIf { it >= 0 }?.let { at -> list.filterIndexed { k, _ -> k != at } } ?: list }
        if (i == _index.value + 1) lookAheadNext()
    }

    /** Moves the song at [from] to [to]; the song playing keeps playing wherever it ends up. */
    fun move(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        q.add(to, q.removeAt(from))
        _queue.value = q
        val now = _index.value
        _index.value = when (now) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) now - 1 else now + 1
            else -> now
        }
        lookAheadNext()
    }

    /** Clears everything after the song playing. */
    fun clearUpcoming() {
        val q = _queue.value
        if (_index.value !in q.indices) return
        _queue.value = q.take(_index.value + 1)
        unshuffled = null
        _shuffled.value = false
    }

    /** A radio from [song]: the song now, then YouTube Music's up-next for it. */
    fun startRadio(song: Song) {
        playAll(listOf(song), 0)
        if (!isYouTubeId(song.videoId)) return
        scope.launch {
            val more = runCatching { InnertubeParser.parseWatchQueue(Innertube.next(song.videoId)) }.getOrDefault(emptyList())
            if (current?.videoId != song.videoId) return@launch
            val have = _queue.value.mapTo(HashSet()) { it.videoId }
            _queue.value = _queue.value + more.filter { it.videoId !in have }
        }
    }

    /** Asks the next song's sources ahead, after the queue's order changed. */
    private fun lookAheadNext() {
        // What comes next may have changed: the one made ready is made again for whatever it is now.
        synchronized(gate) { prepared?.let { if (!it.linked || crossfadeMs == 0L) { (engine as? FfmpegEngine)?.voice?.then = null; dropPrepared() } } }
        val following = _queue.value.getOrNull(_index.value + 1) ?: return
        aheadJob?.cancel()
        aheadJob = scope.launch { lookAhead(following) }
    }

    /** Loads [songs] without playing them, for pictures of the window (DesktopShots). */
    internal fun cue(songs: List<Song>, at: Int) {
        _queue.value = songs
        _index.value = at
    }

    fun playAt(i: Int, atMs: Long = 0) {
        if (i in _queue.value.indices) open(i, atMs)
    }

    fun toggle() {
        if (intercept?.invoke(com.opentune.data.together.Together.Control.PlayPause) == true) return
        val e = synchronized(gate) { engine } ?: return current?.let { open(_index.value) } ?: Unit
        wanted = !wanted
        _wanted.value = wanted
        if (wanted) e.play() else e.pause()
        if (!wanted) AudioBus.pause()
        synchronized(gate) { incoming }?.let { if (wanted) it.play() else it.pause() }
        _playing.value = wanted
    }

    private fun pauseAll() {
        wanted = false
        _wanted.value = false
        synchronized(gate) { listOfNotNull(engine, incoming) }.forEach { it.pause() }
        AudioBus.pause()
        _playing.value = false
    }

    fun next() {
        if (intercept?.invoke(com.opentune.data.together.Together.Control.Next) == true) return
        skipNext()
    }

    /** The next song, whatever a room says; a room's own skip comes here. */
    internal fun skipNext() {
        val i = _index.value + 1
        when {
            i in _queue.value.indices -> open(i)
            _repeat.value == Repeat.ALL && _queue.value.isNotEmpty() -> open(0)
            AppSettings.playback.value.autoplay -> extendThen { open(i) }
            else -> pauseAll()
        }
    }

    /** Stops playback in [minutes], the last few turning the volume down; null turns the timer off. */
    fun sleepIn(minutes: Int?) {
        sleepJob?.cancel()
        setFade(1.0)
        if (minutes == null) {
            _sleep.value = null
            return
        }
        val total = minutes * 60_000L
        val at = System.currentTimeMillis() + total
        _sleep.value = Sleep.At(at, minutes)
        sleepJob = scope.launch {
            // The last third, at most three minutes, fades out, as on the phone.
            val window = minOf(180_000L, total / 3)
            while (true) {
                val left = at - System.currentTimeMillis()
                if (left <= 0) break
                setFade(if (left < window) left.toDouble() / window else 1.0)
                delay(1_000)
            }
            pauseAll()
            setFade(1.0)
            _sleep.value = null
        }
    }

    /** Stops playback when the song playing ends. */
    fun sleepAtEndOfSong() {
        sleepJob?.cancel()
        setFade(1.0)
        _sleep.value = Sleep.EndOfSong
    }

    private fun setFade(f: Double) {
        fade = f
        applyVolume()
    }

    private fun applyVolume() {
        val v = _volume.value * fade
        // Everything on the bus; a bit-perfect FLAC sets its device's own.
        AudioBus.volume = v.toFloat()
        synchronized(gate) { listOfNotNull(engine, incoming) }.forEach { it.setVolume(v) }
    }

    /** Back to the start of the song a few seconds in; before that, the song before. */
    fun previous() {
        if (intercept?.invoke(com.opentune.data.together.Together.Control.Previous) == true) return
        skipPrevious()
    }

    internal fun skipPrevious() {
        if (positionMs() > 4_000) seekTo(0, byHand = false)
        else if (_index.value > 0) open(_index.value - 1)
    }

    /** Moves to [ms] in the song; [byHand] when the listener did it, which a room hears about. */
    fun seekTo(ms: Long, byHand: Boolean = true) {
        if (byHand && intercept?.invoke(com.opentune.data.together.Together.Control.Seek(ms)) == true) return
        // A chain or a fade set for the old place in the song is set again from the new one on the next tick.
        synchronized(gate) { if (prepared?.linked == true) dropPrepared() }
        synchronized(gate) { engine }?.seek(ms)
        if (byHand) _seeks.tryEmit(Unit)
    }

    /** Opens the song playing again where it is, on the output now chosen. */
    internal fun restartOutput() {
        if (current == null || synchronized(gate) { engine } == null) return
        val at = positionMs()
        val playing = wanted
        synchronized(gate) { dropPrepared() }
        // Closed first, so the device chosen now is the one it opens.
        synchronized(gate) { engine?.let(::release); engine = null }
        AudioBus.release()
        open(_index.value, at.coerceAtLeast(1))
        if (!playing) pause()
    }

    /** Puts [songs] after the song playing in place of what was there, as a room's queue changes. */
    internal fun replaceUpcoming(songs: List<Song>) {
        val q = _queue.value
        if (_index.value !in q.indices) return playAll(songs, 0)
        _queue.value = q.take(_index.value + 1) + songs
        unshuffled = null
        _shuffled.value = false
        lookAheadNext()
    }

    fun setVolume(v: Double) {
        _volume.value = v.coerceIn(0.0, 1.0)
        Library.setVolume(_volume.value)
        applyVolume()
    }

    /** The volume before muting, to go back to. */
    private var unmuted = 0.6

    /** Mutes, or puts the volume back to what it was. */
    fun toggleMute() {
        if (_volume.value > 0.0) { unmuted = _volume.value; setVolume(0.0) } else setVolume(unmuted.takeIf { it > 0.0 } ?: 0.6)
    }

    /** [by] milliseconds on (or back), within the song. */
    fun seekBy(by: Long) {
        val length = durationMs()
        seekTo((positionMs() + by).coerceIn(0L, if (length > 0) length - 1_000 else Long.MAX_VALUE))
    }

    fun positionMs(): Long = synchronized(gate) { engine }?.positionMs() ?: 0L
    fun durationMs(): Long = synchronized(gate) { engine }?.durationMs() ?: 0L

    private fun open(i: Int, atMs: Long = 0) {
        val song = _queue.value.getOrNull(i) ?: return
        // The song after this one, already decoding: it takes over at once.
        val ready = synchronized(gate) { prepared?.takeIf { it.song.videoId == song.videoId && atMs == 0L && it.failed == null } }
        if (ready != null) return adopt(ready, i, fresh = true)
        _index.value = i
        _error.value = null
        _loading.value = true
        _quality.value = null
        wanted = true
        _wanted.value = true
        _playing.value = false
        Library.played(song)
        val gen = synchronized(gate) {
            upgradeJob?.cancel()
            aheadJob?.cancel()
            dropPrepared()
            fading.forEach { it.dispose() }; fading.clear()
            incoming?.let(::release); incoming = null
            engine?.let(::release); engine = null
            ++generation
        }
        val found = upgrades[song.videoId]?.takeIf { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS }
        if (found != null) start(song, gen, found, takeover = false, atMs = atMs) else startBase(song, gen, atMs)
        followUp(song, i, gen)
    }

    /** Better sources for the song now playing, then the next song's, so it can start in its best quality. */
    private fun followUp(song: Song, i: Int, gen: Int) {
        upgradeJob = scope.launch {
            findUpgrades(song, gen)
            _queue.value.getOrNull(i + 1)?.let { following -> aheadJob = scope.launch { lookAhead(following) } }
        }
        if (isYouTubeId(song.videoId)) scope.launch { applyLoudness(song.videoId, gen) }
    }

    /** Stops an engine for good; a bit-perfect one gives the device back before the bus takes it. */
    private fun release(e: Engine) {
        e.dispose()
        if (e is FlacEngine) e.awaitClosed(1_000)
    }

    // ---- The song's first source -------------------------------------------------

    /**
     * YouTube through the relay, or your server's own file: what every song starts from.
     * A YouTube song plays its Opus stream, YouTube's best; if that fails, its AAC one.
     */
    private fun startBase(song: Song, gen: Int, atMs: Long = 0, kind: Streams.Kind = Streams.Kind.OPUS) {
        val address = addressOf(song.videoId, kind) ?: return fail(song, gen)
        lateinit var e: FfmpegEngine
        e = FfmpegEngine(
            address, startAtMs = { atMs }, autoplay = wanted,
            onReady = { ms ->
                if (current(gen, e)) {
                    if (ms > 0) _durationOf.value = song.videoId to ms
                    if (_quality.value == null) _quality.value = baseQuality(song, kind)
                }
            },
            onStarted = { if (current(gen, e)) _loading.value = false },
            onPlaying = { on -> if (current(gen, e)) { _playing.value = on; if (on) _loading.value = false } },
            onEnd = { if (current(gen, e)) ended() },
            onError = { message ->
                val mine = synchronized(gate) { (gen == generation && engine === e).also { if (it) engine = null } }
                val at = runCatching { e.positionMs() }.getOrDefault(atMs)
                e.dispose()
                if (!mine) return@FfmpegEngine
                if (kind == Streams.Kind.OPUS && isYouTubeId(song.videoId)) {
                    System.err.println("Player: ${song.videoId}: Opus failed ($message), trying AAC")
                    _quality.value = null
                    startBase(song, gen, at, Streams.Kind.AAC)
                } else {
                    System.err.println("Player: ${song.videoId}: $message")
                    fail(song, gen)
                }
            },
        )
        e.voice.gain = loudnessGain(song.videoId)
        synchronized(gate) {
            if (gen != generation) return e.dispose()
            engine = e
        }
    }

    private fun baseQuality(song: Song, kind: Streams.Kind): Quality = when {
        Subsonic.isSubsonic(song.videoId) -> Quality("Your server", "Original file")
        kind == Streams.Kind.OPUS -> Quality("YouTube", Streams.known(song.videoId, Streams.Kind.OPUS)?.let { "Opus ${it.kbps} kbps" } ?: "Opus")
        else -> Quality("YouTube", Streams.known(song.videoId)?.let { "AAC ${it.kbps} kbps" } ?: "AAC")
    }

    private fun current(gen: Int, e: Engine) = synchronized(gate) { gen == generation && engine === e }

    private fun fail(song: Song, gen: Int) {
        if (synchronized(gate) { gen != generation }) return
        _loading.value = false
        _playing.value = false
        _error.value = "Couldn't play ${song.title}"
    }

    private fun ended() {
        if (_sleep.value == Sleep.EndOfSong) {
            _sleep.value = null
            pauseAll()
        } else if (_repeat.value == Repeat.ONE) {
            open(_index.value)
        } else {
            // A song that ends isn't a skip pressed: a guest in a room doesn't send it to the host.
            skipNext()
        }
    }

    // ---- The next song, ready before it's needed ----------------------------------------

    /** How long before the end the next song starts decoding. */
    private const val PREPARE_AHEAD_MS = 30_000L

    /**
     * The song after this one, decoding into a queued voice on the bus: started on the exact
     * sample the one before ends (gapless), or faded in over the last seconds (crossfade).
     */
    private class Prepared(val song: Song, val upgrade: Upgrade?) {
        lateinit var engine: FfmpegEngine
        /** The generation it plays as once it takes over; until then its callbacks are ignored. */
        @Volatile var gen = -1
        @Volatile var duration = 0L
        @Volatile var failed: String? = null
        /** Chained to the song before, or fading in over it. */
        @Volatile var linked = false
    }

    private var prepared: Prepared? = null
    /** Songs fading out under the next one, until their fade ends. */
    private val fading = mutableListOf<Engine>()

    /** Must be called holding [gate]. Undoes a chain or a crossfade set up into it. */
    private fun dropPrepared() {
        prepared?.let { p ->
            (engine as? FfmpegEngine)?.voice?.let { v ->
                if (v.then === p.engine.voice) v.then = null
                if (v.fadeInto === p.engine.voice) { v.fadeInto = null; v.ramp(null); v.onFadedOut = null }
            }
            p.engine.dispose()
        }
        prepared = null
    }

    private val crossfadeMs: Long get() = AppSettings.playback.value.crossfadeSeconds.coerceIn(0, 12) * 1_000L

    /** Every quarter second: starts the next song decoding near the end, and the crossfade into it. */
    private fun tick() {
        if (!wanted) return
        val e = synchronized(gate) { engine } as? FfmpegEngine ?: return
        if (incoming != null) return
        val length = e.durationMs().takeIf { it > 0 } ?: return
        val left = length - e.positionMs()
        val nextIndex = when {
            _repeat.value == Repeat.ONE || _sleep.value == Sleep.EndOfSong -> return
            _index.value + 1 in _queue.value.indices -> _index.value + 1
            _repeat.value == Repeat.ALL && _queue.value.isNotEmpty() -> 0
            else -> return
        }
        val next = _queue.value[nextIndex]
        val fade = crossfadeMs.takeIf { it > 0 && length > it * 3 }
        val p = synchronized(gate) {
            prepared?.takeIf { it.song.videoId != next.videoId || it.failed != null }?.let { dropPrepared() }
            if (prepared == null && left <= PREPARE_AHEAD_MS + (fade ?: 0)) prepared = prepare(next)
            prepared
        } ?: return
        if (p.linked) return
        if (fade == null) {
            // Gapless: it starts on the sample after this one's last.
            AudioBus.chain(e.voice, p.engine.voice)
            p.linked = true
        } else if (left <= fade + 600 && p.engine.voice.primed) {
            // Set a little ahead, from where the mixer is, so the fade starts on its frame and not on this tick.
            p.linked = true
            val rate = p.engine.voice.rate
            val speed = AppSettings.sound.value.speed.toDouble().coerceIn(0.5, 2.0)
            val fadeFrames = (minOf(fade, left).coerceAtLeast(500) * rate / 1000).toInt()
            val leftFrames = (left / speed * rate / 1000).toLong()
            val after = (leftFrames - fadeFrames - AudioBus.latency).coerceAtLeast(0).toInt()
            e.voice.onFadedOut = { synchronized(gate) { fading.remove(e) }; e.dispose() }
            // The next song shows the moment it starts coming in.
            p.engine.voice.onFadeIn = {
                val ok = synchronized(gate) { (engine === e && prepared === p).also { if (it) { fading += e; engine = null } } }
                if (ok) adopt(p, nextIndex, fresh = false)
            }
            AudioBus.crossfade(e.voice, p.engine.voice, fadeFrames, after)
        }
    }

    /** Must be called holding [gate]. A bit-perfect FLAC can't share the bus, so it isn't made ahead. */
    private fun prepare(song: Song): Prepared? {
        val upgrade = upgrades[song.videoId]?.takeIf { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS && !(it.flac != null && bitPerfect) }
        val p = Prepared(song, upgrade)
        val address = upgrade?.flac?.url ?: upgrade?.saavn ?: addressOf(song.videoId, Streams.Kind.OPUS) ?: return null
        lateinit var e: FfmpegEngine
        e = FfmpegEngine(
            address, startAtMs = { 0 }, autoplay = false,
            onReady = { ms -> p.duration = ms },
            onStarted = { if (current(p.gen, e)) _loading.value = false },
            onPlaying = { on -> if (current(p.gen, e)) _playing.value = on },
            onEnd = { if (current(p.gen, e)) ended() },
            onError = { message ->
                System.err.println("Player: ${song.videoId}: the next song didn't load ahead: $message")
                p.failed = message ?: "failed"
                if (current(p.gen, e)) {
                    synchronized(gate) { engine = null }
                    startBase(song, p.gen, runCatching { e.positionMs() }.getOrDefault(0))
                }
            },
            headers = upgrade?.flac?.headers ?: emptyMap(),
            queued = true,
        )
        e.voice.gain = if (upgrade == null) loudnessGain(song.videoId) else 1f
        p.engine = e
        return p
    }

    /**
     * Makes [p] the song playing, at [i]. [fresh] when it starts now (a skip, or the end of the one
     * before without a chain), rather than having already taken over on the bus.
     */
    private fun adopt(p: Prepared, i: Int, fresh: Boolean) {
        val gen = synchronized(gate) {
            upgradeJob?.cancel()
            aheadJob?.cancel()
            prepared = null
            if (fresh) {
                fading.forEach { it.dispose() }; fading.clear()
                incoming?.let(::release); incoming = null
                engine?.let { old -> (old as? FfmpegEngine)?.voice?.then = null; release(old) }
            }
            engine = p.engine
            ++generation
        }
        p.gen = gen
        if (fresh) {
            wanted = true
            _wanted.value = true
            // A fade set up for it is off: it starts now, at full level.
            p.engine.voice.ramp(null)
            p.engine.voice.onFadeIn = null
            p.engine.voice.start()
            if (wanted) AudioBus.resume()
        }
        _index.value = i
        _error.value = null
        _quality.value = p.upgrade?.let(::qualityOf) ?: baseQuality(p.song, Streams.Kind.OPUS)
        _loading.value = !p.engine.voice.primed
        _playing.value = wanted
        if (p.duration > 0) _durationOf.value = p.song.videoId to p.duration
        Library.played(p.song)
        followUp(p.song, i, gen)
    }

    // ---- Better sources ------------------------------------------------------------

    /** A better source found for a song: what plays it and how it's labelled. */
    private data class Upgrade(val flac: Addons.Flac?, val saavn: String?, val at: Long = System.currentTimeMillis()) {
        val rank get() = if (flac != null) 2 else 1
    }

    /** Found sources by song, for a song played again or coming next. Add-on links expire, so not for long. */
    private val upgrades = ConcurrentHashMap<String, Upgrade>()
    private const val UPGRADE_KEEP_MS = 10 * 60_000L

    /** How good what's playing is: 0 the starting source, 1 JioSaavn, 2 FLAC. */
    private fun rankNow(): Int = when {
        _quality.value?.lossless == true -> 2
        _quality.value?.source == "JioSaavn" -> 1
        else -> 0
    }

    private suspend fun wantedOf(song: Song): JioSaavn.Wanted? {
        val listed = song.durationText?.let(::seconds)
        val length = listed ?: withTimeoutOrNull(15_000) {
            durationOf.first { (id, ms) -> id == song.videoId && ms > 0 }.second / 1000.0
        } ?: return null
        return JioSaavn.Wanted(song.title, song.artist, song.albumName, length)
    }

    /** Asks JioSaavn and the add-ons at once; each better answer takes over as it comes. */
    private suspend fun findUpgrades(song: Song, gen: Int) {
        if (!isYouTubeId(song.videoId) || (!Addons.lossless.value && !Addons.jioSaavn.value)) return
        Addons.syncPublished()
        val wanted = wantedOf(song) ?: return
        coroutineScope {
            val saavn = async { if (Addons.jioSaavn.value) JioSaavn.find(wanted) else null }
            val flac = async { runCatching { Addons.findFlac(wanted) }.getOrNull() }
            saavn.await()?.let { url -> offer(song, gen, Upgrade(null, url)) }
            flac.await()?.let { found -> offer(song, gen, Upgrade(found, null)) }
        }
    }

    /** Finds the next song's sources before it plays. */
    private suspend fun lookAhead(song: Song) {
        if (!isYouTubeId(song.videoId) || upgrades[song.videoId]?.let { System.currentTimeMillis() - it.at < UPGRADE_KEEP_MS } == true) return
        val listed = song.durationText?.let(::seconds) ?: return
        val wanted = JioSaavn.Wanted(song.title, song.artist, song.albumName, listed)
        val flac = runCatching { Addons.findFlac(wanted) }.getOrNull()
        val found = if (flac != null) Upgrade(flac, null) else if (Addons.jioSaavn.value) JioSaavn.find(wanted)?.let { Upgrade(null, it) } else null
        found?.let { upgrades[song.videoId] = it }
    }

    private fun offer(song: Song, gen: Int, upgrade: Upgrade) {
        val known = upgrades[song.videoId]
        if (known == null || known.rank <= upgrade.rank) upgrades[song.videoId] = upgrade
        if (synchronized(gate) { gen == generation } && upgrade.rank > rankNow()) start(song, gen, upgrade, takeover = true)
    }

    /** What a better source is called under the song. */
    private fun qualityOf(upgrade: Upgrade, flac: FlacEngine? = null): Quality = upgrade.flac?.let { f ->
        val heard = when {
            flac == null -> AudioBus.rate.takeIf { it != f.rate }?.let { " · played at ${Addons.khz(it)}" }.orEmpty()
            flac.output?.sampleSizeInBits == 16 && f.bits > 16 -> " · sound card at 16-bit"
            flac.exclusive -> " · bit-perfect"
            else -> ""
        }
        Quality(f.provider, "FLAC ${f.bits}-bit / ${Addons.khz(f.rate)}$heard", lossless = true)
    } ?: Quality("JioSaavn", "AAC 320 kbps")

    /**
     * Plays [upgrade]: taking over from what's playing at its position when [takeover],
     * else from the start. On the bus the old song plays on until the new one's first
     * sound; a bit-perfect FLAC needs the device to itself, so the old one stops first.
     * If the new one fails, the old one carries on (or the song starts over from its
     * first source, when the old one is gone).
     */
    private fun start(song: Song, gen: Int, upgrade: Upgrade, takeover: Boolean, atMs: Long = 0) {
        val direct = upgrade.flac != null && bitPerfect
        var old = synchronized(gate) { engine.takeIf { takeover } }
        var from = atMs
        if (direct && old != null) {
            from = old.positionMs()
            synchronized(gate) { if (engine === old) engine = null; dropPrepared() }
            release(old)
            old = null
        }
        if (direct) AudioBus.release()
        val startAt: () -> Long = { old?.positionMs() ?: from }
        lateinit var e: Engine
        val onStarted: () -> Unit = {
            val replaced = synchronized(gate) {
                if (gen != generation || (incoming !== e && engine !== e)) return@synchronized null
                val before = engine.takeIf { it !== e }
                incoming = null
                engine = e
                before ?: e
            }
            if (replaced != null) {
                if (replaced !== e) replaced.dispose()
                _quality.value = qualityOf(upgrade, e as? FlacEngine)
                _loading.value = false
                _playing.value = wanted
                e.durationMs().takeIf { it > 0 }?.let { _durationOf.value = song.videoId to it }
            }
        }
        val onError: (String?) -> Unit = { message ->
            System.err.println("Player: ${song.videoId}: upgrade failed: $message")
            upgrades.remove(song.videoId)
            val fallBack = synchronized(gate) {
                when {
                    gen != generation -> false
                    incoming === e -> { incoming = null; false }
                    engine === e -> { engine = null; true }
                    else -> false
                }
            }
            val at = runCatching { e.positionMs() }.getOrDefault(0L)
            release(e)
            if (fallBack) { _quality.value = null; startBase(song, gen, at) }
        }
        val onEnd: () -> Unit = { if (current(gen, e)) ended() }
        e = if (direct) {
            val f = upgrade.flac!!
            FlacEngine(
                source = { download(f.url, f.headers) }, startAtMs = startAt, autoplay = wanted,
                onStarted = onStarted, onEnd = onEnd, onError = onError,
                out = if (NativeAudio.available) NativePcmOut(AudioDevices.selected.value, exclusive = true) else SoundCard(),
            )
        } else {
            FfmpegEngine(
                upgrade.flac?.url ?: upgrade.saavn!!, startAtMs = startAt, autoplay = wanted,
                onReady = {}, onStarted = onStarted,
                onPlaying = { on -> if (current(gen, e)) _playing.value = on },
                onEnd = onEnd, onError = onError,
                headers = upgrade.flac?.headers ?: emptyMap(),
            )
        }
        e.setVolume(_volume.value * fade)
        synchronized(gate) {
            if (gen != generation) return release(e)
            if (old != null) { incoming?.let(::release); incoming = e } else engine = e
        }
    }

    private val files = Http.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    /** A FLAC file's bytes as they arrive. */
    private fun download(url: String, headers: Map<String, String>): java.io.InputStream {
        val response = files.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw java.io.IOException("its file server returned HTTP ${response.code}")
        }
        return response.body!!.byteStream()
    }

    /** "3:45" or "1:02:03" in seconds. */
    internal fun seconds(text: String): Double? {
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0) { total, p -> total * 60 + p }.toDouble().takeIf { it > 0 }
    }

    /** For tests: where each song's first source is, in place of YouTube. */
    @Volatile internal var sourceFor: ((String) -> String?)? = null

    /** Settings → Playback → Bit-perfect lossless: an add-on's FLAC straight to the device at its own format. */
    private val bitPerfect: Boolean get() = AudioDevices.bitPerfect.value

    // ---- Loudness ------------------------------------------------------------------

    /** YouTube's own loudness measure of each song, in dB against its reference. */
    private val loudness = ConcurrentHashMap<String, Double>()

    /** The voice gain for [id]: brought to YouTube's reference and the chosen level, as the phone does. */
    private fun loudnessGain(id: String): Float {
        val p = AppSettings.playback.value
        val db = loudness[id]?.takeIf { p.loudnessNormalization } ?: return 1f
        return Math.pow(10.0, Loudness.gainDb(db, p.volumeLevel) / 20.0).toFloat()
    }

    /** Reads the song's loudness from YouTube and, if it's still playing from YouTube, applies it. */
    private suspend fun applyLoudness(id: String, gen: Int) {
        if (!AppSettings.playback.value.loudnessNormalization) return
        if (loudness[id] == null) Loudness.of(id)?.let { loudness[id] = it } ?: return
        val e = synchronized(gate) { engine.takeIf { gen == generation } } as? FfmpegEngine ?: return
        if (_quality.value?.source == "YouTube") e.voice.gain = loudnessGain(id)
    }

    /** Where a song's first source is read from: YouTube through the relay, or your server's own file. */
    private fun addressOf(id: String, kind: Streams.Kind): String? = when {
        sourceFor != null -> sourceFor?.invoke(id)
        Subsonic.isSubsonic(id) -> Subsonic.streamUrl(id)
        else -> Relay.addressOf(id, kind)
    }

    /** Adds YouTube Music's up-next for the last song, then runs [then]. */
    private fun extendThen(then: () -> Unit) {
        // Only YouTube songs have an up-next to follow.
        val last = _queue.value.lastOrNull()?.takeIf { isYouTubeId(it.videoId) } ?: run {
            _playing.value = false
            return
        }
        scope.launch {
            val more = runCatching { InnertubeParser.parseWatchQueue(Innertube.next(last.videoId)) }.getOrDefault(emptyList())
            val have = _queue.value.mapTo(HashSet()) { it.videoId }
            val fresh = more.filter { it.videoId !in have }
            if (fresh.isEmpty()) {
                _playing.value = false
                return@launch
            }
            _queue.value = _queue.value + fresh
            then()
        }
    }
}
