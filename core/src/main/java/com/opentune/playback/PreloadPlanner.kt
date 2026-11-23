package com.opentune.playback

import android.os.SystemClock
import com.opentune.data.DebugLog
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.history.SpeedDial
import com.opentune.data.innertube.StreamResolver
import com.opentune.data.isYouTubeId
import com.opentune.data.library.LibraryStore
import com.opentune.data.lossless.ExternalStreams
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
 * song cache. Which and how much is [PreloadSteps.offQueue]'s choice; the song playing always comes
 * first ([PreloadGate], through [Host.holds]); no more than [AudioBudget]'s audio an hour.
 * Nothing runs with Preload upcoming songs off.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val screens = ConcurrentHashMap<String, List<Song>>()
    @Volatile private var host: Host? = null
    private var job: Job? = null
    private val budget = AudioBudget(now = SystemClock::elapsedRealtime)
    private val readied = ReadiedSongs(now = SystemClock::elapsedRealtime)

    /** [songs] shown under [key] (one screen or section), likeliest first; replaces what [key] showed. */
    fun onScreen(key: String, songs: List<Song>) {
        if (songs.isEmpty()) return offScreen(key)
        if (screens[key] == songs) return
        screens[key] = songs
        poke()
    }

    /** [key] left the screen. */
    fun offScreen(key: String) {
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
    fun poke() {
        synchronized(this) {
            job?.cancel()
            job = scope.launch {
                delay(SETTLE_MS)
                run(host ?: return@launch)
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
        for (songs in screens.values) songs.forEachIndexed { i, s -> candidates += s to PlayChance.onScreen(i, history.of(s.videoId, s.artist)) }
        if (unmetered && h.playing()) {
            SpeedDial.fromHistory(records, limit = BACKGROUND_SPEED_DIAL * 2).mapNotNull { it.videoId }.take(BACKGROUND_SPEED_DIAL)
                .forEachIndexed { i, id ->
                    val r = history.record(id) ?: return@forEachIndexed
                    candidates += Song(id, r.title, r.artist, r.thumbnailUrl, r.durationText) to PlayChance.onScreen(i, history.of(id, r.artist))
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

    private suspend fun ready(h: Host, want: OffQueueWant, history: PreloadHistory) {
        val known = history.record(want.song.videoId)
        val song = want.song.let { s ->
            if (s.durationText != null || known == null) s else s.copy(durationText = known.durationText, artist = s.artist.ifBlank { known.artist })
        }
        val id = song.videoId
        val started = SystemClock.elapsedRealtime()
        try {
            awaitGate(h)
            StreamResolver.resolve(id)
            if (ExternalStreams.allowed(false)) {
                awaitGate(h)
                withTimeoutOrNull(SAAVN_WAIT_MS) { ExternalStreams.findJioSaavn(song, song.durationMillis()) }
            }
            val length = song.durationMillis()
            if (length > 0 && AppSettings.lyrics.value.ordered.any { it.enabled }) {
                awaitGate(h)
                LyricsRepository.prefetch(id, song.title, song.artist, length)
            }
            var fetched = 0L
            if (want.audio && !AppSettings.onMeteredNetwork()) {
                val bytes = if (ExternalStreams.cached(id)?.let { !it.lossless && it.mimeType == null } == true) START_BYTES_SAAVN else START_BYTES_YOUTUBE
                if (budget.left() >= bytes) {
                    awaitGate(h)
                    fetched = fetchWhileFree(h, song, bytes)
                    budget.spend(fetched)
                }
            }
            readied.readied(id)
            DebugLog.d(TAG, "$id readied off the queue (chance ${"%.2f".format(want.chance)}): address, " +
                (if (ExternalStreams.allowed(false)) "JioSaavn, " else "") + "lyrics" +
                (if (fetched > 0) ", ${fetched / 1024} kB of audio" else "") + " in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readied.failed(id)
            DebugLog.w(TAG, "$id couldn't be readied off the queue: ${e.message}")
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
}
