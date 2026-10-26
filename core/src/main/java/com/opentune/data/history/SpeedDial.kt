package com.opentune.data.history

import com.opentune.data.model.ShelfItem
import kotlin.math.exp

/**
 * Home's Speed dial, made on the phone when YouTube Music doesn't send its
 * own (signed out, or not offered): the songs played most, with recent plays
 * counting for more, so last month's favourite gives way to this week's.
 * A play that was skipped within a few seconds doesn't count.
 */
object SpeedDial {
    const val TITLE = "Speed dial"
    /** Three pages of nine on Home; the full page shows the rest. */
    const val ON_HOME = 27
    private const val HALF_LIFE_DAYS = 21.0
    private const val SKIPPED_MS = 10_000L
    private const val DAY_MS = 86_400_000L

    fun fromHistory(
        records: List<PlayRecord>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 60,
        /** Albums, playlists and artists played from their pages (see [ListenedCollections]). */
        collections: List<CollectionPlay> = emptyList(),
    ): List<ShelfItem> {
        class Tally(val item: ShelfItem, var score: Double, var last: Long)
        fun weight(at: Long) = exp(-((now - at).coerceAtLeast(0L).toDouble() / DAY_MS) * Math.log(2.0) / HALF_LIFE_DAYS)
        val tallies = LinkedHashMap<String, Tally>()
        for (r in records) {
            // An old record has no listened time; a short one was skipped.
            if (r.listenedMs in 1 until SKIPPED_MS) continue
            val t = tallies.getOrPut(r.videoId) {
                Tally(ShelfItem(r.title, r.artist, r.thumbnailUrl, r.videoId, null, explicit = r.explicit == true), 0.0, r.playedAt)
            }
            t.score += weight(r.playedAt)
            if (r.playedAt > t.last) t.last = r.playedAt
        }
        // A whole album or playlist put on counts as a few songs' worth: its songs are
        // counted too, but one at a time, so a list played often still earns its place.
        for (c in collections) {
            if (c.playedAt.isEmpty()) continue
            tallies[c.browseId] = Tally(
                ShelfItem(c.title, c.subtitle, c.thumbnailUrl, null, c.browseId),
                c.playedAt.sumOf { weight(it) } * COLLECTION_WEIGHT,
                c.playedAt.max(),
            )
        }
        return tallies.values
            .sortedWith(compareByDescending<Tally> { it.score }.thenByDescending { it.last })
            .take(limit)
            .map { it.item }
    }

    private const val COLLECTION_WEIGHT = 3.0

    /** Recent local listening takes effect immediately, while account picks fill unused places. */
    fun personalized(local: List<ShelfItem>, account: List<ShelfItem>, limit: Int = 60): List<ShelfItem> =
        (local + account).distinctBy { it.videoId ?: it.browseId ?: it.title }.take(limit)

    /** YouTube Music's own Speed dial shelf among Home's, if it sent one. */
    fun isSpeedDial(title: String): Boolean = title.trim().equals(TITLE, ignoreCase = true)
}
