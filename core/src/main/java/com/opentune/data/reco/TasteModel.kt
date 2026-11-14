package com.opentune.data.reco

import android.content.Context
import android.content.SharedPreferences
import com.opentune.data.history.PlayRecord
import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import kotlin.math.exp
import kotlin.math.ln

/**
 * What the listener keeps listening to, learned on the phone from what they
 * let play and what they skip, and used to put the radio's picks in order.
 *
 * Each pick is described by a few numbers (how high the radio placed it, how
 * much of this artist they play and how much of it they finish, whether
 * they've skipped this song before, liked it, whether it's a video or a
 * version), and a logistic model turns those into the chance it gets played
 * through. Every AutoPlay song that's played through or skipped moves the
 * model's weights a little towards the right answer (online gradient
 * descent), so the order improves as they listen. It starts from weights that
 * already lean the sensible way, so it's useful from the first song.
 * Nothing leaves the phone.
 */
object TasteModel {
    /** What's known about the listener's history, gathered once per ranking. */
    class Taste(records: List<PlayRecord>, val liked: Set<String>) {
        internal val artistPlays = HashMap<String, Int>()
        internal val artistFinished = HashMap<String, Double>()
        internal val songSkips = HashMap<String, Int>()
        internal val songPlays = HashMap<String, Int>()
        internal val maxArtistPlays: Int

        init {
            for (r in records) {
                val a = artistKey(r.artist)
                artistPlays.merge(a, 1, Int::plus)
                val length = r.durationText.durationMillis()
                val done = if (length != null && length > 0) (r.listenedMs.toDouble() / length).coerceIn(0.0, 1.0) else 0.6
                artistFinished.merge(a, done, Double::plus)
                songPlays.merge(r.videoId, 1, Int::plus)
                if (r.listenedMs in 1 until SKIP_MS) songSkips.merge(r.videoId, 1, Int::plus)
            }
            maxArtistPlays = artistPlays.values.maxOrNull() ?: 0
        }
    }

    internal const val FEATURES = 9
    private val START = doubleArrayOf(
        0.2, // bias
        1.6, // the radio's own rank, 1 at the top
        0.9, // how much of this artist is played (log, scaled)
        1.2, // how much of this artist's songs are finished
        -2.2, // skipped this song before
        1.0, // liked
        -0.5, // a music video
        -0.6, // a version (live, slowed, cover...)
        0.3, // never played: something new
    )
    private const val RATE = 0.05
    private const val L2 = 0.001
    internal const val SKIP_MS = 30_000L

    private var prefs: SharedPreferences? = null
    @Volatile private var weights = START.copyOf()

    fun init(context: Context) {
        val p = context.getSharedPreferences("taste_model", Context.MODE_PRIVATE)
        prefs = p
        p.getString("w", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == FEATURES }?.let { weights = it.toDoubleArray() }
    }

    internal fun features(song: Song, radioRank: Double, taste: Taste): DoubleArray {
        val a = artistKey(song.artist)
        val plays = taste.artistPlays[a] ?: 0
        val finished = if (plays > 0) (taste.artistFinished[a] ?: 0.0) / plays else 0.5
        return doubleArrayOf(
            1.0,
            radioRank,
            if (taste.maxArtistPlays > 0) ln(1.0 + plays) / ln(1.0 + taste.maxArtistPlays) else 0.0,
            finished - 0.5,
            if ((taste.songSkips[song.videoId] ?: 0) > 0) 1.0 else 0.0,
            if (song.videoId in taste.liked) 1.0 else 0.0,
            if (song.isVideo) 1.0 else 0.0,
            if (SongIdentity.isVersion(song)) 1.0 else 0.0,
            if ((taste.songPlays[song.videoId] ?: 0) == 0) 1.0 else 0.0,
        )
    }

    /** The chance [song] is played through, 0..1. */
    fun score(song: Song, radioRank: Double, taste: Taste, w: DoubleArray = weights): Double {
        val x = features(song, radioRank, taste)
        var z = 0.0
        for (i in 0 until FEATURES) z += w[i] * x[i]
        return 1.0 / (1.0 + exp(-z))
    }

    /** [picks] (the radio's order) put in the order they're most likely to be played through. */
    fun rank(picks: List<Song>, taste: Taste): List<Song> {
        if (picks.size <= 1) return picks
        val w = weights
        return picks.withIndex()
            .map { (i, s) -> s to score(s, 1.0 - i.toDouble() / picks.size, taste, w) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * An AutoPlay song finished: [playedThrough] when it was listened to, false
     * when it was skipped early. The weights take one small step towards that.
     */
    fun learn(song: Song, playedThrough: Boolean, taste: Taste, radioRank: Double = 0.5) {
        val w = weights.copyOf()
        val x = features(song, radioRank, taste)
        val p = score(song, radioRank, taste, w)
        val err = (if (playedThrough) 1.0 else 0.0) - p
        for (i in 0 until FEATURES) w[i] += RATE * (err * x[i] - L2 * w[i])
        weights = w
        prefs?.edit()?.putString("w", w.joinToString(","))?.apply()
    }

    /** The weights now, for tests. */
    internal fun current(): DoubleArray = weights.copyOf()

    internal fun reset() { weights = START.copyOf() }

    private fun artistKey(artist: String) = HiddenList.normalize(HiddenList.mainArtist(artist))
}
