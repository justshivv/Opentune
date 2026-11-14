package com.opentune.data.reco

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Home's own order. The top stays put (greeting, Speed dial, the daylist, Quick picks). Under it,
 * the rows made for you (daily mixes, genres, Discover, Spotify's, the community's) are ranked
 * by how often you play from each, weighed toward lately, with a little shuffle each day (and at
 * each pull to refresh) among rows that rank about the same. YouTube Music's shelves are ranked
 * the same way, by what you play from them, and laid in between, so no two of your mix rows
 * come one after another while there are shelves to put between them.
 */
object HomeLayout {
    /** One of the rows made for you: [id], and how high it starts before anything's played from it. */
    data class Personal(val id: String, val base: Double)

    /** A shelf left with fewer than this many cards, once repeats are taken out, isn't shown. */
    const val MIN_CARDS = 3

    /**
     * YouTube Music's shelves as Home shows them, in that order, without repeats: a card already
     * on a shelf higher up is left off the later ones ("Featured on" for two artists you like often
     * lists the same playlists twice), and a shelf left with fewer than [MIN_CARDS] cards is null,
     * not shown. One that came with fewer to start with keeps them, if none were repeats.
     */
    fun withoutRepeats(ordered: List<com.opentune.data.model.HomeShelf>): List<com.opentune.data.model.HomeShelf?> {
        val seen = HashSet<String>()
        return ordered.map { shelf ->
            val fresh = shelf.items.filter { item ->
                val key = item.browseId?.removePrefix("VL") ?: item.videoId ?: "${item.title}|${item.subtitle}".lowercase()
                seen.add(key)
            }
            when {
                fresh.size == shelf.items.size -> shelf
                fresh.size >= MIN_CARDS -> shelf.copy(items = fresh)
                else -> null
            }
        }
    }

    /** How much a day's shuffle can move a row against the ones around it. */
    private const val JITTER = 0.35

    /**
     * The order of [personal] rows and [shelves] (YouTube Music's, by key, in the order they came)
     * below Home's fixed top: rows made for you, with shelves between them, one after the first and
     * two after each one after that. [played] is how much each has been played from (0 for none).
     */
    fun arrange(personal: List<Personal>, shelves: List<String>, played: (String) -> Double, seed: Long): List<String> {
        val r = Random(seed)
        val mine = personal.map { it.id to it.base + played(it.id) + r.nextDouble() * JITTER }
            .sortedByDescending { it.second }.map { it.first }
        // Shelves: the ones played from more first; otherwise as YouTube Music ordered them.
        val theirs = shelves.withIndex().map { (i, k) -> k to played(k) - i * 0.01 }
            .sortedByDescending { it.second }.map { it.first }
        val out = ArrayList<String>(mine.size + theirs.size)
        val shelf = theirs.iterator()
        mine.forEachIndexed { i, id ->
            out += id
            repeat(if (i == 0) 1 else 2) { if (shelf.hasNext()) out += shelf.next() }
        }
        shelf.forEach { out += it }
        return out
    }
}

/**
 * What's been played from each Home row, kept on the phone: each play adds one, and the total
 * halves every [HALF_LIFE_DAYS] days, so the order follows what you play now, not a year ago.
 */
object HomeEngagement {
    private const val HALF_LIFE_DAYS = 14.0
    private const val DAY = 24 * 60 * 60 * 1000.0
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow<Map<String, Pair<Double, Long>>>(emptyMap())
    /** Every row's raw count and when it was last added to, by key. */
    val counts: StateFlow<Map<String, Pair<Double, Long>>> = state.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("home_engagement", Context.MODE_PRIVATE)
        prefs = p
        state.value = p.all.mapNotNull { (k, v) ->
            val parts = (v as? String)?.split('|') ?: return@mapNotNull null
            val n = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            k to (n to at)
        }.toMap()
    }

    /** A play started from row [key]. */
    fun record(key: String, nowMs: Long = System.currentTimeMillis()) {
        val now = decayed(state.value[key], nowMs) + 1.0
        state.value = state.value + (key to (now to nowMs))
        prefs?.edit()?.putString(key, "$now|$nowMs")?.apply()
    }

    /** How much row [key] counts toward Home's order: up to about one, for a row played from often lately. */
    fun weight(key: String, counts: Map<String, Pair<Double, Long>> = state.value, nowMs: Long = System.currentTimeMillis()): Double =
        weightOf(decayed(counts[key], nowMs))

    internal fun weightOf(n: Double): Double = 1.0 - exp(-n / 6.0)

    internal fun decayed(entry: Pair<Double, Long>?, nowMs: Long): Double {
        val (n, at) = entry ?: return 0.0
        val days = ((nowMs - at) / DAY).coerceAtLeast(0.0)
        return n * 0.5.pow(days / HALF_LIFE_DAYS)
    }

    /** The key a YouTube Music shelf goes by: its title, which stays the same day to day. */
    fun shelfKey(title: String) = "shelf:" + title.trim().lowercase()
}
