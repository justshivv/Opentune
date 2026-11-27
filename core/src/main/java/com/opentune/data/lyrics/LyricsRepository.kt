package com.opentune.data.lyrics

import com.opentune.data.isYouTubeId
import com.opentune.data.DebugLog as Log
import com.opentune.data.Http
import com.opentune.data.innertube.Innertube
import com.opentune.data.local.LocalMusic
import com.opentune.data.podcasts.Podcasts
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.opentune.data.download.Downloads
import com.opentune.data.settings.LyricsSource
import com.opentune.data.settings.AppSettings
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Lyrics from LRCLIB (lrclib.net): free, keyless, and mostly line-synced, with
 * word sync for some tracks; from LyricsPlus (see [LyricsPlus]), often word
 * by word; from Better Lyrics and its Portato (see [BetterLyrics]), word by
 * word; from SimpMusic (see [SimpMusic]), matched by YouTube video;
 * from KuGou (see [KuGou]) and NetEase (see
 * [NetEase]), line-synced; and from YouTube Music, unsynced.
 *
 * Every enabled source is asked at once. Each answer is tidied and checked
 * against the track (see [LyricsQuality]): one timed for another length,
 * running past the song's end, saying only "instrumental", or with words
 * the other sources don't share is left out. Of the rest, word-by-word
 * lyrics beat line-synced ones, which beat plain text, and the order set in
 * Settings › Lyrics sources decides between equals. Plain text never stops
 * the search, and while it's all there is, sources that were down are asked
 * again a little later (see [lyricsUpdates]). When the other sources agree
 * the chosen lyrics run early or late by a steady amount, that's corrected
 * through [Lyrics.Synced.autoOffsetMs]. What's found is kept (see
 * [LyricsDisk]); kept lyrics short of word sync are asked again in the
 * background as their song plays, now and then (see [lyricsUpdates]). Within
 * LRCLIB: an exact match on title, artist and duration; then a search on
 * title and artist, preferring synced results whose duration is closest;
 * then a free-text search on the cleaned title. YouTube Music's lyrics are
 * unsynced and never used for local files.
 */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "OpenTune (https://github.com/justshivv/Opentune)"
    private const val TAG = "Lyrics"
    /** A card's kind of item, once mistaken for the artist. */
    private val NOT_AN_ARTIST = setOf("song", "video", "single", "ep", "album")

    /** A search hit further than this from the track's duration is a different recording. */
    private const val DURATION_TOLERANCE_S = 8.0
    /** Within this, a hit is the same recording. */
    private const val CLOSE_S = 2.0

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Lyrics>()

    /** Lyrics saved with a download, so they show offline. */
    private fun offlineFile(videoId: String): File? = Downloads.lyricsFileFor(videoId)

    private fun readOffline(videoId: String, durationMs: Long): Lyrics? =
        offlineFile(videoId)?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(Lyrics.serializer(), f.readText()) }.getOrNull()
        }?.let { LyricsQuality.tidy(it, durationMs) }

    /** Fetches and keeps the lyrics for a downloaded song; called by the download job. */
    suspend fun saveOffline(videoId: String, title: String, artist: String, durationMs: Long) {
        val f = offlineFile(videoId) ?: return
        val found = lyricsFor(videoId, title, artist, durationMs) ?: return
        withContext(Dispatchers.IO) { runCatching { f.writeText(json.encodeToString(Lyrics.serializer(), found)) } }
    }

    suspend fun lyricsFor(videoId: String, title: String, artist: String, durationMs: Long): Lyrics? =
        find(videoId, title, artist, durationMs).lyrics

    /**
     * Finds a song's lyrics before it plays, so they show the moment it starts. [durationMs] may
     * be a card's, to the second: the player's own figure finds them within [CLOSE_S] of it.
     */
    suspend fun prefetch(videoId: String, title: String, artist: String, durationMs: Long) {
        if (durationMs > 0) find(videoId, title, artist, durationMs)
    }

    /** The other lengths, in seconds either side, lyrics readied ahead are found under. */
    private val NEAR_SECONDS = listOf(-1L, 1L, -2L, 2L)

    /**
     * The lyrics for a song, then better ones if they turn up: when only
     * plain text (or nothing, or line sync where word sync is wanted) was
     * found while a source was down or slow, the sources are asked again
     * after each of [RETRY_WAITS_MS], answers already in being reused.
     *
     * [videoTimed] takes lyrics timed to the playing video itself (its own captions) onto
     * the song's clock, the one every other source times to; by default they're the same.
     */
    fun lyricsUpdates(
        videoId: String, title: String, artist: String, durationMs: Long,
        videoTimed: (Lyrics) -> Lyrics = { it },
    ): Flow<Lyrics?> = flow {
        val asked = android.os.SystemClock.elapsedRealtime()
        var found = find(videoId, title, artist, durationMs, videoTimed)
        Log.d("Preload", "$videoId: lyrics ${if (found.lyrics != null) "shown" else "not found"} in ${android.os.SystemClock.elapsedRealtime() - asked} ms")
        emit(found.lyrics)
        // Kept from before a line at a time where word sync is wanted: the sources are asked
        // again behind what's shown, for this one song as it plays, in case one has its words
        // timed by now (a source added since, a song a source has learnt). See [recheckDue].
        if (found.recheck) {
            rechecked.add(videoId)
            val again = find(videoId, title, artist, durationMs, videoTimed, fresh = true)
            val better = rank(again.lyrics) > rank(found.lyrics)
            if (again.settled) {
                withContext(Dispatchers.IO) { LyricsDisk.markChecked(videoId) }
                val key = found.key
                val kept = if (better) again.lyrics else found.lyrics
                if (key != null && kept != null) keep(videoId, key, kept)
            }
            if (better) emit(again.lyrics)
            found = Found(if (better) again.lyrics else found.lyrics, true)
        }
        for (wait in RETRY_WAITS_MS) {
            if (found.settled) break
            delay(wait)
            val again = find(videoId, title, artist, durationMs, videoTimed)
            if (rank(again.lyrics) > rank(found.lyrics)) {
                found = again
                emit(again.lyrics)
            } else {
                found = Found(found.lyrics, again.settled)
            }
        }
    }

    private val RETRY_WAITS_MS = listOf(15_000L, 45_000L)

    /** Kept lyrics below the sync wanted are asked again at most this often, as their song plays. */
    private const val RECHECK_MS = 14L * 24 * 60 * 60 * 1000

    /** Songs whose kept lyrics were asked again this launch, done or under way. */
    private val rechecked = ConcurrentHashMap.newKeySet<String>()

    /** Whether [videoId]'s kept lyrics are due to be asked again (see [lyricsUpdates]). */
    private suspend fun recheckDue(videoId: String): Boolean =
        videoId !in rechecked && System.currentTimeMillis() - withContext(Dispatchers.IO) { LyricsDisk.checkedAt(videoId) } > RECHECK_MS

    /**
     * What a search came to; [settled] when asking again can't do better. [recheck] when
     * it's kept lyrics that may have better since, under [key].
     */
    private class Found(val lyrics: Lyrics?, val settled: Boolean, val recheck: Boolean = false, val key: String? = null)

    /**
     * [fresh] passes over what's kept and keeps nothing itself: the caller compares the
     * answer with what's kept first.
     */
    private suspend fun find(
        videoId: String, title: String, credited: String, durationMs: Long,
        videoTimed: (Lyrics) -> Lyrics = { it }, fresh: Boolean = false,
    ): Found {
        // Songs saved before the fix carry a card's "Song" or "Video" as their artist: looked up
        // under it, the lyrics found were another song's.
        val artist = credited.takeUnless { it.trim().lowercase() in NOT_AN_ARTIST }.orEmpty()
        withContext(Dispatchers.IO) { readOffline(videoId, durationMs) }?.let { return Found(it, true) }
        // A podcast episode has no lyrics anywhere, and its title can match a song's: its words are its transcript.
        if (isYouTubeId(videoId) && Podcasts.isEpisode(videoId)) return transcriptOf(videoId, durationMs)
        val settings = AppSettings.lyrics.value
        val chosen = settings.chosen[videoId]
        // The duration is in the key: lyrics found for one length aren't reused for another.
        val tail = "|${settings.ordered.filter { it.enabled }.joinToString(",") { it.source.name }}|${settings.preferWordSynced}|$chosen"
        val seconds = durationMs / 1000
        val key = "$videoId|$seconds$tail"
        // Readied ahead from a card, whose length is only to the second (see [prefetch]): within
        // [CLOSE_S] it's the same recording.
        fun inMemory(): Lyrics? = cache[key]
            ?: if (durationMs > 0) NEAR_SECONDS.firstNotNullOfOrNull { off -> cache["$videoId|${seconds + off}$tail"] } else null
        val enabled = settings.ordered.filter { it.enabled }.map { it.source }
        val target = if (settings.preferWordSynced) WORD_SYNCED else LINE_SYNCED
        // Kept lyrics short of the sync wanted are shown, and asked again behind them when due
        // (not a source picked by hand for the song: that's the one wanted, whatever it has).
        suspend fun kept(lyrics: Lyrics, since: Boolean = false) =
            Found(lyrics, true, recheck = chosen == null && rank(lyrics) < target && (since || recheckDue(videoId)), key = key)
        if (!fresh) {
            inMemory()?.let { return kept(it) }
            // Kept from an earlier launch (see [LyricsDisk]): read once per song a launch.
            if (durationMs > 0 && readFromDisk.add(videoId)) {
                withContext(Dispatchers.IO) { LyricsDisk.read(videoId) }.forEach { (k, v) -> cache.putIfAbsent(k, v) }
                inMemory()?.let { return kept(it) }
            }
            // Kept before a source was added: still good to show at once. Word by word, they're
            // kept as these settings' too; short of it, the new source may have better.
            if (durationMs > 0) keptBefore(videoId, seconds, enabled.map { it.name }, settings.preferWordSynced, chosen)?.let { before ->
                if (rank(before) >= target) keep(videoId, key, before) else cache[key] = before
                return kept(before, since = true)
            }
        }
        // The video's own captions are timed to it; every other source to the song.
        fun clocked(source: LyricsSource, lyrics: Lyrics) = if (source == LyricsSource.YOUTUBE_CAPTIONS) videoTimed(lyrics) else lyrics
        // A source picked for this song from the player goes first, on its own, as it comes.
        if (chosen != null) {
            answerOf(chosen, videoId, title, artist, durationMs).await().getOrNull()?.let { LyricsQuality.tidy(clocked(chosen, it), durationMs) }?.let { picked ->
                if (durationMs > 0 && !fresh) keep(videoId, key, picked)
                return Found(picked, true, key = key)
            }
        }
        // Every source is asked at once, as Echo Music does, and the answers
        // are read in the user's order: a slow source no longer holds up the
        // ones after it, and the order still decides between them.
        val pending = enabled.map { source -> source to answerOf(source, videoId, title, artist, durationMs) }
        val vetted = HashMap<Int, LyricsQuality.Candidate?>()
        // The answers in so far, each tidied and checked once.
        suspend fun inHand(): List<LyricsQuality.Candidate> = pending.indices.mapNotNull { i ->
            val (source, answer) = pending[i]
            if (!answer.isCompleted) return@mapNotNull null
            if (i !in vetted) vetted[i] = answer.await().getOrNull()?.let { vet(i, source, clocked(source, it), durationMs, videoId) }
            vetted[i]
        }
        fun referencesIn(all: List<LyricsQuality.Candidate>, of: LyricsQuality.Candidate) =
            all.filter { it !== of }.mapNotNull { it.lyrics as? Lyrics.Synced }
        // Word timing is held to the other sources' lines; a video's captions are timed to be
        // read on screen, not to the singing, so they don't count against it.
        fun trusted(c: LyricsQuality.Candidate, all: List<LyricsQuality.Candidate>): Boolean =
            rank(c.lyrics) < WORD_SYNCED || LyricsQuality.wordsTrusted(
                c.lyrics as Lyrics.Synced, referencesIn(all.filter { it.source != LyricsSource.YOUTUBE_CAPTIONS }, c), durationMs,
            )
        val deadline = System.nanoTime() + BUDGET_MS * 1_000_000
        var failed = false
        for ((i, entry) in pending.withIndex()) {
            val (source, answer) = entry
            val wait = (deadline - System.nanoTime()) / 1_000_000
            // Past the budget, only answers already in are read.
            val outcome = if (answer.isCompleted || wait > 0) withTimeoutOrNull(wait.coerceAtLeast(1)) { answer.await() } else null
            if (outcome == null || outcome.isFailure) {
                // YouTube Music only ever has plain text: it can't make up for anything.
                if (source != LyricsSource.YOUTUBE_MUSIC) failed = true
                outcome?.exceptionOrNull()?.let { Log.w(TAG, "${source.label} lookup failed for $videoId", it) }
                continue
            }
            if (rank(outcome.getOrNull()) < target) continue
            // Good enough to stop at, unless it doesn't fit the track, the answers in
            // hand say it's another song, or its word timing can't be trusted (then a
            // source further down may have words that can).
            val hand = inHand()
            val best = LyricsQuality.choose(hand, target)
            if (best != null && best.order <= i && rank(best.lyrics) >= target && trusted(best, hand)) break
        }
        val all = inHand()
        val best = LyricsQuality.choose(all, target)
        fun references(of: LyricsQuality.Candidate) = referencesIn(all, of)
        // Steady early or late timing, as the other sources see it; the
        // per-song offset, once set, is used instead (see [lyricsOffset]).
        fun shifted(c: LyricsQuality.Candidate): Lyrics? {
            val synced = c.lyrics as? Lyrics.Synced ?: return c.lyrics
            if (durationMs <= 0) return synced
            val shift = LyricsQuality.autoOffset(synced, references(c), durationMs)
            if (shift == 0L) return synced
            Log.d(TAG, "${synced.source} lyrics for $videoId moved ${-shift} ms to match the other sources")
            return synced.copy(autoOffsetMs = shift)
        }
        var result = best?.let(::shifted)
        // Word timing that can't be trusted on this track shows wrong words lit:
        // another source's that can be, or these same lyrics a line at a time.
        if (best != null && result is Lyrics.Synced && rank(result) == WORD_SYNCED && !trusted(best, all)) {
            val other = all.asSequence()
                .filter { it !== best && rank(it.lyrics) == WORD_SYNCED && !LyricsQuality.outlier(it, all) }
                .sortedBy { it.order }
                .firstOrNull { trusted(it, all) }
            Log.d(TAG, "${result.source} word timing for $videoId not trusted; ${other?.source?.label ?: "showing lines"}")
            result = other?.let(::shifted) ?: LyricsQuality.asLines(result)
        }
        // Only remember answers matched on the song's length (without it any
        // version's lyrics can match, timed for another cut), and only ones
        // asking again couldn't better.
        val settled = !failed || rank(result) >= target
        if (result != null && durationMs > 0 && settled && !fresh) keep(videoId, key, result)
        return Found(result, settled, key = key)
    }

    /**
     * What was kept for [videoId] at [seconds] (or a second or two off) under the same
     * settings but for sources added since: the sources then, in their order, are the ones
     * on now less the new ones. Null when nothing like that was kept.
     */
    private fun keptBefore(videoId: String, seconds: Long, enabled: List<String>, preferWordSynced: Boolean, chosen: LyricsSource?): Lyrics? =
        cache.entries.firstNotNullOfOrNull { (k, v) ->
            if (!k.startsWith("$videoId|")) return@firstNotNullOfOrNull null
            v.takeIf { keptUnderEarlierSources(k.removePrefix("$videoId|"), seconds, enabled, preferWordSynced, chosen) }
        }

    /** Whether a kept entry's key ([rest], after the song's id) is these settings' before sources were added. */
    internal fun keptUnderEarlierSources(rest: String, seconds: Long, enabled: List<String>, preferWordSynced: Boolean, chosen: LyricsSource?): Boolean {
        val parts = rest.split('|')
        if (parts.size != 4) return false
        val at = parts[0].toLongOrNull() ?: return false
        if (abs(at - seconds) > NEAR_SECONDS.maxOf { abs(it) }) return false
        if (parts[2] != preferWordSynced.toString() || parts[3] != chosen.toString()) return false
        val before = parts[1].split(',').filter { it.isNotEmpty() }
        return before.size < enabled.size && enabled.filter { it in before } == before
    }

    /** Songs whose lyrics kept on disk were read this launch. */
    private val readFromDisk = ConcurrentHashMap.newKeySet<String>()

    /** Remembers [lyrics] for this launch and the next ones (see [LyricsDisk]). */
    private fun keep(videoId: String, key: String, lyrics: Lyrics) {
        cache[key] = lyrics
        lookups.launch { runCatching { LyricsDisk.write(videoId, key, lyrics) } }
    }

    /** Whether lyrics for [videoId] are at hand, for this launch or kept from an earlier one. */
    fun readyFor(videoId: String): Boolean =
        cache.keys.any { it.startsWith("$videoId|") } || LyricsDisk.has(videoId)

    /** A podcast episode's transcript (see [PodcastTranscripts]); asked again later when YouTube didn't answer. */
    private suspend fun transcriptOf(videoId: String, durationMs: Long): Found {
        val key = "transcript|$videoId"
        cache[key]?.let { return Found(it, true) }
        val answer = withContext(Dispatchers.IO) { runCatching { PodcastTranscripts.lookup(videoId, durationMs) } }
        answer.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "Transcript lookup failed for $videoId", it) }
        val found = answer.getOrNull()
        if (found != null) cache[key] = found
        // None there (captions off) is an answer; a failure may be the network.
        return Found(found, answer.isSuccess)
    }

    /** [lyrics] tidied, or null (with the reason logged) when it doesn't fit this track. */
    private fun vet(order: Int, source: LyricsSource, lyrics: Lyrics, durationMs: Long, videoId: String): LyricsQuality.Candidate? {
        val problem = problemWith(lyrics, durationMs)
        if (problem != null) {
            Log.d(TAG, "${source.label} lyrics for $videoId left out: $problem")
            return null
        }
        return LyricsQuality.Candidate(order, source, LyricsQuality.tidy(lyrics, durationMs))
    }

    /** Why the automatic choice passes over [lyrics] for a track [durationMs] long; null when it doesn't. */
    fun problemWith(lyrics: Lyrics, durationMs: Long): String? =
        if (lyrics is Lyrics.Synced && LyricsQuality.untimed(lyrics)) "Not really timed"
        else LyricsQuality.problem(LyricsQuality.tidy(lyrics, durationMs), durationMs)

    /**
     * Every source's answer for one song, as each comes in, for the player's
     * "Lyrics source" sheet. Sources switched off in Settings are asked too:
     * this is where to see whether one would help.
     */
    fun everySource(videoId: String, title: String, artist: String, durationMs: Long): Flow<Pair<LyricsSource, Result<Lyrics?>>> = channelFlow {
        LyricsSource.entries.forEach { source ->
            launch { send(source to answerOf(source, videoId, title, artist, durationMs).await()) }
        }
    }

    internal fun rank(lyrics: Lyrics?): Int = when (lyrics) {
        null -> 0
        is Lyrics.Plain -> 1
        is Lyrics.Synced -> if (lyrics.lines.any { it.wordSynced }) WORD_SYNCED else LINE_SYNCED
    }

    internal const val LINE_SYNCED = 2
    internal const val WORD_SYNCED = 3

    /** Lookups outlive the caller's wait: one still out finishes in the background, for the next ask. */
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long the sources get, all together, before the best answer in hand is shown. */
    private const val BUDGET_MS = 12_000L

    /** Lyrics each source found, by source and song, so asking again only asks the ones that hadn't. */
    private val found = ConcurrentHashMap<String, Result<Lyrics?>>()
    /** Lookups still out, so a second ask waits on the same one rather than starting another. */
    private val running = ConcurrentHashMap<String, Deferred<Result<Lyrics?>>>()

    private fun answerOf(source: LyricsSource, videoId: String, title: String, artist: String, durationMs: Long): Deferred<Result<Lyrics?>> {
        val k = "${source.name}|$videoId|${durationMs / 1000}|$title|$artist"
        found[k]?.let { return CompletableDeferred(it) }
        running[k]?.takeIf { it.isActive }?.let { return it }
        if (found.size > 400) found.clear()
        return lookups.async {
            ask(source, videoId, title, artist, durationMs).also { r ->
                // Nothing found, or a failure, may be a source that was down: those are asked again.
                if (r.getOrNull() != null) found[k] = r
                running.remove(k)
            }
        }.also { running[k] = it }
    }

    private suspend fun ask(source: LyricsSource, videoId: String, title: String, artist: String, durationMs: Long): Result<Lyrics?> = runCatching {
        when (source) {
            LyricsSource.LRC_RED -> LrcRed.lookup(title, artist, durationMs)
            LyricsSource.PAXSENIX -> Paxsenix.lookup(title, artist, durationMs)
            LyricsSource.LRCLIB -> lookup(title, artist, durationMs)
            LyricsSource.LYRICSPLUS -> LyricsPlus.lookup(title, artist, durationMs)
            LyricsSource.BETTERLYRICS -> BetterLyrics.lookup(title, artist, durationMs)
            LyricsSource.BETTERLYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs)
            LyricsSource.SIMPMUSIC -> if (!isYouTubeId(videoId)) null else runInterruptible { SimpMusic.lookup(videoId, durationMs) }
            LyricsSource.KUGOU -> runInterruptible { KuGou.lookup(title, artist, durationMs) }
            LyricsSource.NETEASE -> runInterruptible { NetEase.lookup(title, artist, durationMs) }
            LyricsSource.UNISON -> if (!isYouTubeId(videoId)) null else runInterruptible { Unison.lookup(videoId, title, artist, durationMs) }
            LyricsSource.YOUTUBE_CAPTIONS -> if (!isYouTubeId(videoId)) null else YouTubeCaptions.lookup(videoId, durationMs)
            LyricsSource.YOUTUBE_MUSIC -> if (!isYouTubeId(videoId)) null else youTubeMusic(videoId)
        }?.let(LyricsQuality::unescaped)
    }

    private suspend fun lookup(title: String, artist: String, durationMs: Long): Lyrics? = coroutineScope {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val durationS = durationMs / 1000.0

        // Each step returns at once with synced lyrics that fit the track;
        // plain text is kept in case no later step finds them in time.
        var plain: Lyrics? = null
        fun take(found: Lyrics?): Lyrics? {
            if (found is Lyrics.Synced && problemWith(found, durationMs) == null) return found
            if (plain == null && found is Lyrics.Plain) plain = found
            return null
        }

        // The exact match and the search by fields go out together, so a slow
        // or busy exact lookup doesn't hold up the search behind it.
        val exact = if (durationMs > 0) async(Dispatchers.IO) {
            runCatching {
                request(
                    "get",
                    "track_name" to cleaned.title,
                    "artist_name" to cleaned.artist,
                    "duration" to durationS.toLong().toString(),
                ) as? JsonObject
            }
        } else null
        val byFields = async(Dispatchers.IO) { runCatching { request("search", "track_name" to cleaned.title, "artist_name" to cleaned.artist) } }
        val e = exact?.await()
        take(e?.getOrNull()?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        val f = byFields.await()
        take(pick(f.getOrNull(), durationS, cleaned.title)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        // Both turned away rather than empty: LRCLIB is down, which the caller counts.
        if ((e == null || e.isFailure) && f.isFailure) throw f.exceptionOrNull()!!

        val byQuery = request("search", "q" to "${cleaned.artist} ${cleaned.title}")
        take(pick(byQuery, durationS, cleaned.title, cleaned.artist)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }

        // The title alone, for an artist LRCLIB spells another way (accents, a
        // transliteration, a "feat." in the artist field). Only a hit by the
        // same artist counts, so a cover's words never sync to this song.
        val byTitle = request("search", "track_name" to cleaned.title)
        take(pick(sameArtist(byTitle, cleaned.artist), durationS, cleaned.title)?.let { toLyrics(it, durationMs) })?.let { return@coroutineScope it }
        plain
    }

    /** The results whose artist matches [artist] loosely: one name inside the other, ignoring case, accents and punctuation. */
    internal fun sameArtist(results: JsonElement?, artist: String): JsonArray {
        val want = looseName(artist)
        if (want.length < 2) return JsonArray(emptyList())
        return JsonArray((results as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { o ->
            val got = looseName(o.str("artistName").orEmpty())
            got.length >= 2 && (got.contains(want) || want.contains(got))
        })
    }

    private fun looseName(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * YouTube Music's own lyrics: unsynced text from LyricFind and others.
     * The watch queue links a Lyrics tab by browse id (`MPLYt…`); browsing
     * it returns the text in a description shelf.
     */
    private suspend fun youTubeMusic(videoId: String): Lyrics? {
        val browseId = findString(Innertube.next(videoId)) { key, value -> key == "browseId" && value.startsWith("MPLYt") }
            ?: return null
        val page = Innertube.browse(browseId)
        val shelf = findObject(page, "musicDescriptionShelfRenderer") ?: return null
        val text = (shelf["description"] as? JsonObject)?.runsText()?.takeIf { it.isNotBlank() } ?: return null
        return Lyrics.Plain(text.trim(), "YouTube Music")
    }

    private fun JsonObject.runsText(): String =
        (this["runs"] as? JsonArray).orEmpty().joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }

    private fun findString(node: JsonElement, match: (String, String) -> Boolean): String? = when (node) {
        is JsonObject -> node.entries.firstNotNullOfOrNull { (k, v) ->
            val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (str != null && match(k, str)) str else findString(v, match)
        }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, match) }
        else -> null
    }

    private fun findObject(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { findObject(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findObject(it, key) }
        else -> null
    }

    /**
     * The best search hit for a track [durationS] long: the synced result
     * closest in length within [CLOSE_S], else the closest synced one within
     * [DURATION_TOLERANCE_S], else the closest plain one. A recording a few
     * seconds longer (an intro, a radio edit) puts every line off by that
     * much, so length matters more than the order LRCLIB lists them in.
     * A hit under another [title] (or, for a free-text search, by another
     * [artist]), or marked instrumental, is passed over.
     */
    internal fun pick(results: JsonElement?, durationS: Double, title: String? = null, artist: String? = null): JsonObject? {
        val all = (results as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { title == null || LyricsQuality.sameTitle(title, it.str("trackName")) }
            .filter { artist == null || LyricsQuality.sameArtist(artist, it.str("artistName")) }
            .filterNot { (it["instrumental"] as? JsonPrimitive)?.booleanOrNull == true }
        fun gap(o: JsonObject) = if (durationS <= 0) 0.0 else abs((o.num("duration") ?: durationS) - durationS)
        val near = all.filter { durationS <= 0 || gap(it) <= DURATION_TOLERANCE_S }.sortedBy(::gap)
        val synced = near.filter { !it.str("syncedLyrics").isNullOrBlank() }
        return synced.firstOrNull { gap(it) <= CLOSE_S }
            ?: synced.firstOrNull()
            ?: near.firstOrNull { !it.str("plainLyrics").isNullOrBlank() }
    }

    private fun toLyrics(obj: JsonObject, durationMs: Long): Lyrics? {
        if ((obj["instrumental"] as? JsonPrimitive)?.booleanOrNull == true) return null
        obj.str("syncedLyrics")?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = LrcParser.parse(lrc, durationMs)
            val recordingMs = obj.num("duration")?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: 0
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB", recordingMs = recordingMs)
        }
        return obj.str("plainLyrics")?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it.trim(), "LRCLIB") }
    }

    private fun request(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        // LRCLIB is often busy (503) or rate-limits (429) for a moment; a
        // short wait and another try usually gets through.
        for (attempt in 0..RETRIES) {
            val call = Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            val busy = call.execute().use { response ->
                if (response.code == 404) return null
                if (response.code == 429 || response.code in 500..599) {
                    if (attempt == RETRIES) throw java.io.IOException("LRCLIB answered ${response.code}")
                    true
                } else {
                    if (!response.isSuccessful) throw java.io.IOException("LRCLIB answered ${response.code}")
                    return response.body?.string()?.let(json::parseToJsonElement)
                }
            }
            if (busy) Thread.sleep(RETRY_WAIT_MS * (attempt + 1))
        }
        return null
    }

    private const val RETRIES = 2
    private const val RETRY_WAIT_MS = 900L

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
