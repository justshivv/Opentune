package com.opentune.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.opentune.data.DebugLog
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.history.SpeedDial
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.isYouTubeId
import com.opentune.data.library.LibraryStore
import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.lossless.SaavnMatches
import com.opentune.data.lyrics.LyricsRepository
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import com.opentune.data.podcasts.Podcasts
import com.opentune.data.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Readies songs the listener is likely to start from outside the queue: ones on the screen (Speed
 * dial, an album's first tracks, the top search results) and, on Wi-Fi while something plays, the
 * top of Speed dial. Each gets its stream address, its JioSaavn match and its lyrics, so it starts
 * at once with its words; on Wi-Fi the likeliest also get the first seconds of their audio in the
 * song cache. Which and how much is [PreloadSteps.offQueue]'s choice, weighed by how often each
 * kind of screen is really played from ([OriginStats]); the song playing always comes first
 * ([PreloadGate], through [Host.holds]); no more than [AudioBudget]'s audio an hour. A song
 * pressed is readied at once ([pressed]). Each song started is logged with where it came from and
 * what of it was ready ([started]). Nothing is readied with Preload upcoming songs off.
 */
object PreloadPlanner {
    /** What the playback service lends the planner. */
    internal interface Host {
        /** Whether the song playing needs the connection to itself just now (see [PreloadGate]). */
        suspend fun holds(): Boolean
        /** Whether anything is playing. */
        suspend fun playing(): Boolean
        /** The songs in the queue now, which the queue's own preload sees to. */
        suspend fun queued(): Set<String>
        /** Fetches the first [bytes] of [song]'s audio, from the source it would start on, into the song cache; the bytes fetched. */
        suspend fun fetchStart(song: Song, bytes: Long): Long
    }

    private const val TAG = "Preload"
    private const val SETTLE_MS = 600L
    private const val GATE_POLL_MS = 500L
    private const val SAAVN_WAIT_MS = 8_000L
    /** Speed dial songs readied in the background, on Wi-Fi while something plays. */
    private const val BACKGROUND_SPEED_DIAL = 3
    /** About five seconds: YouTube's Opus at ~160 kbps, JioSaavn at 320. */
    private const val START_BYTES_YOUTUBE = 100_000L
    private const val START_BYTES_SAAVN = 200_000L
    /** A song started this long after its press still counts the press's readying. */
    private const val PRESS_COUNTS_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val screens = ConcurrentHashMap<String, List<Song>>()
    @Volatile private var host: Host? = null
    private var job: Job? = null
    private val budget = AudioBudget(now = SystemClock::elapsedRealtime)
    private val readied = ReadiedSongs(now = SystemClock::elapsedRealtime)
    /** Songs whose audio start was fetched ahead, for [started]'s report. */
    private val audioAhead = ConcurrentHashMap.newKeySet<String>()

    private val origins = OriginStats()
    @Volatile private var prefs: SharedPreferences? = null
    /** Screens shown now whose visit has already had a song started from it. */
    private val visitStarted = ConcurrentHashMap.newKeySet<String>()

    /** Reads what the screens' visits came to before ([OriginStats]). */
    fun init(context: Context) {
        val p = context.getSharedPreferences("preload-origins", Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getStringSet("counts", emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) null else parts[0] to ((parts[1].toIntOrNull() ?: 0) to (parts[2].toIntOrNull() ?: 0))
        }.toMap()
        origins.load(saved)
    }

    private fun saveOrigins() {
        val p = prefs ?: return
        p.edit().putStringSet("counts", origins.counts().mapTo(HashSet()) { (o, c) -> "$o|${c.first}|${c.second}" }).apply()
    }

    /**
     * [songs] shown under [key] (one screen or section), likeliest first; replaces what [key] showed.
     * [now]: plan at once, for a screen the listener taps from as soon as it shows (search results).
     */
    fun onScreen(key: String, songs: List<Song>, now: Boolean = false) {
        if (songs.isEmpty()) return offScreen(key)
        val before = screens.put(key, songs)
        if (before == songs) return
        if (before == null) { origins.shown(OriginStats.origin(key)); saveOrigins() }
        poke(if (now) 0L else SETTLE_MS)
    }

    /** [key] left the screen. */
    fun offScreen(key: String) {
        visitStarted.remove(key)
        if (screens.remove(key) != null) poke()
    }

    private var lyricsJob: Job? = null
    /** Songs ahead whose lyrics were looked for; none found isn't asked again at every queue change. */
    private val lyricsAsked = ReadiedSongs(now = SystemClock::elapsedRealtime)

    /** Finds the lyrics of [songs] (the queue's songs ahead the plan picked), once the song playing lets it. */
    internal fun queueLyrics(songs: List<Song>) {
        val h = host ?: return
        if (AppSettings.lyrics.value.ordered.none { it.enabled }) return
        val due = songs.filter { it.durationMillis() > 0 && lyricsAsked.due(it.videoId) }
        if (due.isEmpty()) return
        synchronized(this) {
            lyricsJob?.cancel()
            lyricsJob = scope.launch {
                delay(SETTLE_MS)
                for (song in due) {
                    awaitGate(h)
                    try {
                        LyricsRepository.prefetch(song.videoId, song.title, song.artist, song.durationMillis())
                        lyricsAsked.readied(song.videoId)
                        DebugLog.d(TAG, "${song.videoId}: lyrics readied ahead")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lyricsAsked.failed(song.videoId)
                    }
                }
            }
        }
    }

    internal fun attach(h: Host) { host = h; poke() }

    internal fun detach(h: Host) {
        if (host !== h) return
        host = null
        synchronized(this) { job?.cancel() }
    }

    /** Plans again, once things have settled: the screen, the queue, the network or the setting changed. */
    fun poke() = poke(SETTLE_MS)

    private fun poke(settleMs: Long) {
        synchronized(this) {
            job?.cancel()
            job = scope.launch {
                if (settleMs > 0) delay(settleMs)
                run(host ?: return@launch)
            }
        }
    }

    private var pressJob: Job? = null
    @Volatile private var pressedAt = 0L
    @Volatile private var pressedId: String? = null

    /**
     * [song] was pressed and held a moment without scrolling: it's likely to be tapped, so its
     * address, JioSaavn and lyrics are looked up now, ahead of the song playing (which the tap
     * replaces), and the tap's start joins those lookups where they've got to.
     */
    fun pressed(song: Song) {
        if (!AppSettings.playback.value.preloadUpcoming) return
        val id = song.videoId
        if (!isYouTubeId(id)) return
        val now = SystemClock.elapsedRealtime()
        if (id == pressedId && now - pressedAt < PRESS_COUNTS_MS) return
        pressedId = id
        pressedAt = now
        if (!readied.due(id)) return
        synchronized(this) {
            pressJob?.cancel()
            // Called on the main thread, from a touch: the checks that read files go with the rest.
            pressJob = scope.launch {
                if (Podcasts.isEpisode(id) || Downloads.fileFor(id) != null) return@launch
                val records = History.records.value
                val liked = LibraryStore.liked.value
                val history = PreloadHistory.of(records, liked) { liked.mapTo(HashSet()) { it.videoId } }
                ready(host, OffQueueWant(song, 1.0, audio = false), history, gated = false, why = "on press")
            }
        }
    }

    private suspend fun run(h: Host) {
        if (!AppSettings.playback.value.preloadUpcoming) return
        val unmetered = !AppSettings.onMeteredNetwork()
        val records = History.records.value
        val liked = LibraryStore.liked.value
        val history = PreloadHistory.of(records, liked) { liked.mapTo(HashSet()) { it.videoId } }
        val queued = h.queued()
        val candidates = ArrayList<Pair<Song, Double>>()
        for ((key, songs) in screens) {
            val factor = origins.factor(OriginStats.origin(key))
            songs.forEachIndexed { i, s -> candidates += s to (PlayChance.onScreen(i, history.of(s.videoId, s.artist)) * factor).coerceAtMost(1.0) }
        }
        if (unmetered && h.playing()) {
            val factor = origins.factor(SPEED_DIAL)
            SpeedDial.fromHistory(records, limit = BACKGROUND_SPEED_DIAL * 2).mapNotNull { it.videoId }.take(BACKGROUND_SPEED_DIAL)
                .forEachIndexed { i, id ->
                    val r = history.record(id) ?: return@forEachIndexed
                    candidates += Song(id, r.title, r.artist, r.thumbnailUrl, r.durationText) to (PlayChance.onScreen(i, history.of(id, r.artist)) * factor).coerceAtMost(1.0)
                }
        }
        val eligible = candidates.filter { (s, _) ->
            isYouTubeId(s.videoId) && !Podcasts.isEpisode(s.videoId) && s.videoId !in queued &&
                Downloads.fileFor(s.videoId) == null && readied.due(s.videoId)
        }
        val wants = PreloadSteps.offQueue(eligible, unmetered)
        for (want in wants) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ready(h, want, history)
        }
    }

    /**
     * Readies [want]: [gated], only while the song playing doesn't need the connection; a song
     * pressed isn't, as the tap that follows replaces what plays. [h] null (no player yet): no
     * audio is fetched.
     */
    private suspend fun ready(h: Host?, want: OffQueueWant, history: PreloadHistory, gated: Boolean = true, why: String = "off the queue") {
        val known = history.record(want.song.videoId)
        val song = want.song.let { s ->
            if (s.durationText != null || known == null) s else s.copy(durationText = known.durationText, artist = s.artist.ifBlank { known.artist })
        }
        val id = song.videoId
        val started = SystemClock.elapsedRealtime()
        try {
            // The three lookups at once: a tap soon after joins each of them where it has got to
            // (each keeps running for whoever asks next), so starting them together starts the song sooner.
            if (gated && h != null) awaitGate(h)
            val length = song.durationMillis()
            kotlinx.coroutines.coroutineScope {
                val address = async { StreamResolver.resolve(id) }
                if (ExternalStreams.allowed(false)) launch {
                    try { withTimeoutOrNull(SAAVN_WAIT_MS) { ExternalStreams.findJioSaavn(song, length) } }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { DebugLog.w(TAG, "$id: JioSaavn ahead: ${e.message}") }
                }
                if (length > 0 && AppSettings.lyrics.value.ordered.any { it.enabled }) launch {
                    try { LyricsRepository.prefetch(id, song.title, song.artist, length) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { DebugLog.w(TAG, "$id: lyrics ahead: ${e.message}") }
                }
                address.await()
            }
            var fetched = 0L
            if (h != null && want.audio && !AppSettings.onMeteredNetwork()) {
                val bytes = if (ExternalStreams.cached(id)?.let { !it.lossless && it.mimeType == null } == true) START_BYTES_SAAVN else START_BYTES_YOUTUBE
                if (budget.left() >= bytes) {
                    awaitGate(h)
                    fetched = fetchWhileFree(h, song, bytes)
                    budget.spend(fetched)
                    if (fetched > 0) audioAhead += id
                }
            }
            readied.readied(id)
            DebugLog.d(TAG, "$id readied $why (chance ${"%.2f".format(want.chance)}): address, " +
                (if (ExternalStreams.allowed(false)) "JioSaavn, " else "") + "lyrics" +
                (if (fetched > 0) ", ${fetched / 1024} kB of audio" else "") + " in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readied.failed(id)
            DebugLog.w(TAG, "$id couldn't be readied $why: ${e.message}")
        }
    }

    /** Waits while the song playing needs the connection. */
    private suspend fun awaitGate(h: Host) {
        var logged = false
        while (h.holds()) {
            if (!logged) { DebugLog.d(TAG, "Waiting for the song playing to load"); logged = true }
            delay(GATE_POLL_MS)
        }
    }

    /** The start of [song]'s audio, fetched only while the song playing doesn't need the connection: called off when it does. */
    private suspend fun fetchWhileFree(h: Host, song: Song, bytes: Long): Long = kotlinx.coroutines.coroutineScope {
        val fetch = async { h.fetchStart(song, bytes) }
        val watch = launch {
            while (true) {
                delay(GATE_POLL_MS)
                if (h.holds()) { DebugLog.d(TAG, "${song.videoId}: audio ahead called off, the song playing needs the connection"); fetch.cancel(); break }
            }
        }
        try { fetch.await() } catch (e: CancellationException) { if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e; 0L } finally { watch.cancel() }
    }

    private const val SPEED_DIAL = "speed-dial"
    @Volatile private var startedId: String? = null

    /**
     * A song started: [fromQueue] when the queue moved onto it (it ended, or next was pressed).
     * Logs where it came from and what of it was ready, and counts the start for the screen it
     * was shown on ([OriginStats]).
     */
    internal fun started(videoId: String, fromQueue: Boolean) {
        val shownOn = screens.entries.firstOrNull { (_, songs) -> songs.any { it.videoId == videoId } }?.key
        val origin = when {
            fromQueue -> "the queue"
            shownOn != null -> OriginStats.origin(shownOn)
            else -> "elsewhere"
        }
        if (!fromQueue && shownOn != null && visitStarted.add(shownOn)) { origins.started(origin); saveOrigins() }
        val pressed = videoId == pressedId && SystemClock.elapsedRealtime() - pressedAt < PRESS_COUNTS_MS
        val saavn = when {
            !ExternalStreams.allowed(false) -> "off"
            ExternalStreams.cached(videoId)?.let { !it.lossless && it.mimeType == null } == true -> "ready"
            SaavnMatches.book()?.idFor(videoId) != null -> "match known"
            SaavnMatches.book()?.missed(videoId) == true -> "none (known)"
            else -> "not ready"
        }
        DebugLog.d(TAG, "Started $videoId from $origin${if (pressed) " (pressed first)" else ""}: " +
            "address ${if (StreamResolver.isFresh(videoId)) "ready" else "not ready"}, JioSaavn $saavn, " +
            "lyrics ${if (LyricsRepository.readyFor(videoId)) "ready" else "not ready"}, " +
            "audio ${if (videoId in audioAhead || fromQueue) "ahead" else "not ahead"}")
        startedId = videoId
    }

    /** The song [started] last began to sound, [ms] after it was asked to start. */
    internal fun sounded(videoId: String, ms: Long) {
        if (videoId != startedId) return
        startedId = null
        DebugLog.d(TAG, "$videoId: sound in $ms ms")
    }
}
