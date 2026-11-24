package com.opentune.playback

import com.opentune.data.history.PlayRecord
import com.opentune.data.model.QueueTier
import com.opentune.data.model.durationMillis

/**
 * What the listener's history says about a song, for [PlayChance]: how often it was played, how
 * often it was skipped within [SKIP_MS], whether they like it, and how much of its artist's songs
 * they usually hear through.
 */
internal data class SongHistory(
    val plays: Int = 0,
    val skips: Int = 0,
    val liked: Boolean = false,
    /** Of the artist's plays, the share heard through; null when the artist was never played. */
    val artistFinished: Double? = null,
) {
    companion object {
        /** A play shorter than this was a skip, as [com.opentune.data.reco.TasteModel] counts it. */
        const val SKIP_MS = 30_000L
    }
}

/** The listening history gathered once, to look songs up in for [PlayChance]. */
internal class PreloadHistory(records: List<PlayRecord>, private val liked: Set<String>) {
    companion object {
        @Volatile private var last: Triple<List<PlayRecord>, Any, PreloadHistory>? = null

        /**
         * The history for [records] and [likedSource] (the liked list as the store holds it), gathered
         * again only when either is a new list: the queue plans at every change, and the history
         * runs to thousands of plays.
         */
        fun of(records: List<PlayRecord>, likedSource: Any, liked: () -> Set<String>): PreloadHistory {
            last?.let { (r, l, h) -> if (r === records && l === likedSource) return h }
            return PreloadHistory(records, liked()).also { last = Triple(records, likedSource, it) }
        }
    }

    private val plays = HashMap<String, Int>()
    private val skips = HashMap<String, Int>()
    private val artistPlays = HashMap<String, Int>()
    private val artistDone = HashMap<String, Double>()
    /** The latest record of each song, for its length and artist when a card shows neither. */
    private val latest = HashMap<String, PlayRecord>()

    init {
        for (r in records) {
            plays.merge(r.videoId, 1, Int::plus)
            if (r.listenedMs in 1 until SongHistory.SKIP_MS) skips.merge(r.videoId, 1, Int::plus)
            val artist = artistKey(r.artist)
            if (artist.isNotEmpty()) {
                artistPlays.merge(artist, 1, Int::plus)
                val length = r.durationText.durationMillis()
                val done = if (length > 0) (r.listenedMs.toDouble() / length).coerceIn(0.0, 1.0) else 0.6
                artistDone.merge(artist, done, Double::plus)
            }
            val known = latest[r.videoId]
            if (known == null || r.playedAt > known.playedAt) latest[r.videoId] = r
        }
    }

    fun of(videoId: String, artist: String): SongHistory {
        val a = artistKey(artist)
        val n = artistPlays[a] ?: 0
        return SongHistory(
            plays = plays[videoId] ?: 0,
            skips = skips[videoId] ?: 0,
            liked = videoId in liked,
            artistFinished = if (n > 0) (artistDone[a] ?: 0.0) / n else null,
        )
    }

    /** The last time [videoId] was played, for its length and artist. */
    fun record(videoId: String): PlayRecord? = latest[videoId]

    private fun artistKey(artist: String) = artist.substringBefore(',').substringBefore(" & ").trim().lowercase()
}

/**
 * The chance, 0..1, that the listener will actually hear a song that's coming up: one in the
 * queue, by its place and the part of the queue it's in, or one on the screen, by its place there;
 * either way moved by what [SongHistory] says. A fixed rule, not a learned model, so it can be
 * reasoned about and tested.
 */
internal object PlayChance {
    /** Worth readying fully: the next song's longer buffer, the second song's FLAC, its lyrics. */
    const val LIKELY = 0.6
    /** Worth readying lightly: its address and JioSaavn match. */
    const val WORTH = 0.3

    /** [position] 0 is the song after the one playing. */
    fun inQueue(position: Int, tier: QueueTier, history: SongHistory): Double {
        var p = 0.9 * Math.pow(0.8, position.toDouble())
        p *= when (tier) {
            QueueTier.USER -> 1.0
            QueueTier.CONTEXT -> 0.95
            QueueTier.AUTOPLAY -> 0.75
        }
        return (p * historyFactor(history)).coerceIn(0.0, 1.0)
    }

    /** [rank] 0 is the most likely song shown. */
    fun onScreen(rank: Int, history: SongHistory): Double {
        var p = 0.5 * Math.pow(0.8, rank.toDouble())
        if (history.plays >= 3 && history.skips * 2 < history.plays) p *= 1.3
        return (p * skipFactor(history)).coerceIn(0.0, 1.0)
    }

    private fun historyFactor(h: SongHistory): Double {
        var f = skipFactor(h)
        if (h.liked) f *= 1.1
        if ((h.artistFinished ?: 0.0) > 0.7) f *= 1.05
        return f
    }

    /** Skipped and never heard through. */
    private fun skipFactor(h: SongHistory): Double = if (h.skips > 0 && h.skips >= h.plays) 0.5 else 1.0
}

/** What's readied for the songs in the queue, by their position after the one playing (0 is next). */
internal data class QueuePlan(
    val saavn: Set<Int>,
    val lossless: Set<Int>,
    val warm: Set<Int>,
    val lyrics: Set<Int>,
    /** How much of the next song ExoPlayer loads ahead. */
    val nextBufferUs: Long,
) {
    companion object {
        val NONE = QueuePlan(emptySet(), emptySet(), emptySet(), emptySet(), 0L)
    }
}

/** One song off the queue to ready, and whether the start of its audio is fetched too. */
internal data class OffQueueWant(val song: com.opentune.data.model.Song, val chance: Double, val audio: Boolean)

internal object PreloadSteps {
    const val SAAVN_REACH = 5
    const val WARM_REACH = 3
    const val BUFFER_US = 10_000_000L
    const val BUFFER_LIKELY_US = 20_000_000L
    /** Songs off the queue readied at once, and of them, how many get the start of their audio. */
    const val OFF_QUEUE_MAX = 3
    const val OFF_QUEUE_AUDIO_MAX = 3

    /**
     * From [chances] (the queue's songs after the one playing, in play order), what's readied:
     * the next song always, the rest as their chance earns it. [unmetered] and [boost] (Deepen on
     * Wi-Fi) give the next song its longer buffer.
     */
    fun forQueue(chances: List<Double>, unmetered: Boolean, boost: Boolean): QueuePlan {
        if (chances.isEmpty()) return QueuePlan(emptySet(), emptySet(), emptySet(), emptySet(), BUFFER_US)
        val saavn = chances.indices.filter { it == 0 || (it < SAAVN_REACH && chances[it] >= PlayChance.WORTH) }.toSet()
        val lossless = chances.indices.filter { it == 0 || (it == 1 && chances[it] >= PlayChance.LIKELY) }.toSet()
        val warm = chances.indices.filter { it == 0 || (it < WARM_REACH && chances[it] >= PlayChance.WORTH) }.toSet()
        val lyrics = chances.indices.filter { it == 0 || (it == 1 && chances[it] >= PlayChance.LIKELY) }.toSet()
        val longer = unmetered && (boost || chances[0] >= PlayChance.LIKELY)
        return QueuePlan(saavn, lossless, warm, lyrics, if (longer) BUFFER_LIKELY_US else BUFFER_US)
    }

    /**
     * Of [candidates] (songs off the queue with their chance; the first of any song counts), the
     * ones readied: the [OFF_QUEUE_MAX] likeliest worth it, and on an [unmetered] network the
     * likeliest of those get the start of their audio too.
     */
    fun offQueue(candidates: List<Pair<com.opentune.data.model.Song, Double>>, unmetered: Boolean): List<OffQueueWant> =
        candidates.distinctBy { it.first.videoId }
            .filter { it.second >= PlayChance.WORTH }
            .sortedByDescending { it.second }
            .take(OFF_QUEUE_MAX)
            .mapIndexed { i, (song, chance) -> OffQueueWant(song, chance, unmetered && i < OFF_QUEUE_AUDIO_MAX) }
}

/**
 * Whether readying anything ahead has to wait for the song playing: while its source is being
 * chosen, while it buffers, or while it has less than [AHEAD_MS] loaded ([AHEAD_SLOW_MS] on a slow
 * connection), so the song heard always gets the connection first.
 */
internal object PreloadGate {
    const val AHEAD_MS = 15_000L
    const val AHEAD_SLOW_MS = 30_000L
    /** Below this estimate (kbps) the connection counts as slow. */
    const val SLOW_KBPS = 1_500

    fun holds(choosing: Boolean, buffering: Boolean, playing: Boolean, aheadMs: Long, remainingMs: Long, slow: Boolean): Boolean {
        if (choosing || buffering) return true
        if (!playing) return false
        // A song nearly over has all it needs: what's left of it is less than the margin.
        val need = minOf(if (slow) AHEAD_SLOW_MS else AHEAD_MS, remainingMs.coerceAtLeast(0))
        return aheadMs < need
    }
}

/** At most [limitBytes] of audio fetched ahead in any [windowMs]. */
internal class AudioBudget(
    private val limitBytes: Long = 20L * 1024 * 1024,
    private val windowMs: Long = 60 * 60_000L,
    private val now: () -> Long,
) {
    private val spent = ArrayDeque<Pair<Long, Long>>()

    @Synchronized fun left(): Long {
        val t = now()
        while (spent.isNotEmpty() && t - spent.first().first >= windowMs) spent.removeFirst()
        return limitBytes - spent.sumOf { it.second }
    }

    @Synchronized fun spend(bytes: Long) {
        if (bytes > 0) spent.addLast(now() to bytes)
    }
}

/** When each song was last readied or failed, so it's neither readied again soon nor retried at once. */
internal class ReadiedSongs(
    private val doneMs: Long = 15 * 60_000L,
    private val failedMs: Long = 5 * 60_000L,
    private val now: () -> Long,
) {
    private val done = HashMap<String, Long>()
    private val failed = HashMap<String, Long>()

    @Synchronized fun due(id: String): Boolean {
        val t = now()
        done[id]?.let { if (t - it < doneMs) return false }
        failed[id]?.let { if (t - it < failedMs) return false }
        return true
    }

    @Synchronized fun readied(id: String) { done[id] = now(); failed.remove(id); trim() }
    @Synchronized fun failed(id: String) { failed[id] = now(); trim() }

    private fun trim() {
        if (done.size + failed.size < 256) return
        val t = now()
        done.entries.removeAll { t - it.value >= doneMs }
        failed.entries.removeAll { t - it.value >= failedMs }
    }
}

/**
 * How often a visit to each kind of screen ([origin] of its key: "search", "collection",
 * "speed-dial") ends with a song started from it, so the screens the listener really plays from
 * get their songs readied first. A screen with no record counts as average.
 */
internal class OriginStats {
    private val shown = HashMap<String, Int>()
    private val started = HashMap<String, Int>()

    @Synchronized fun shown(origin: String) {
        shown.merge(origin, 1, Int::plus)
        // Old habits fade: halved once a screen has many visits on record.
        if ((shown[origin] ?: 0) > FADE_AT) {
            shown[origin] = (shown[origin] ?: 0) / 2
            started[origin] = (started[origin] ?: 0) / 2
        }
    }

    @Synchronized fun started(origin: String) { started.merge(origin, 1, Int::plus) }

    /** How much likelier than average a song shown on [origin] is to be started, [MIN]..[MAX]. */
    @Synchronized fun factor(origin: String): Double {
        val allShown = shown.values.sum()
        val allStarted = started.values.sum().coerceAtMost(allShown)
        val average = (allStarted + 1.0) / (allShown + 3.0)
        val s = shown[origin] ?: 0
        val st = (started[origin] ?: 0).coerceAtMost(s)
        val rate = (st + WEIGHT * average) / (s + WEIGHT)
        return (rate / average).coerceIn(MIN, MAX)
    }

    @Synchronized fun counts(): Map<String, Pair<Int, Int>> =
        (shown.keys + started.keys).associateWith { (shown[it] ?: 0) to (started[it] ?: 0) }

    @Synchronized fun load(counts: Map<String, Pair<Int, Int>>) {
        counts.forEach { (o, c) -> shown[o] = c.first; started[o] = c.second }
    }

    companion object {
        /** Visits it takes for a screen's own record to count as much as the average. */
        const val WEIGHT = 5.0
        /** Never so low that the first song of a screen falls under [PlayChance.WORTH]. */
        const val MIN = 0.6
        const val MAX = 1.6
        const val FADE_AT = 200

        /** The kind of screen a [PreloadPlanner.onScreen] key names. */
        fun origin(key: String): String = key.substringBefore(':')
    }
}
