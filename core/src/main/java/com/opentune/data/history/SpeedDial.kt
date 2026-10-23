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

    fun fromHistory(records: List<PlayRecord>, now: Long = System.currentTimeMillis(), limit: Int = 60): List<ShelfItem> {
        class Tally(val record: PlayRecord, var score: Double, var last: Long)
        val tallies = LinkedHashMap<String, Tally>()
        for (r in records) {
            // An old record has no listened time; a short one was skipped.
            if (r.listenedMs in 1 until SKIPPED_MS) continue
            val ageDays = (now - r.playedAt).coerceAtLeast(0L).toDouble() / DAY_MS
            val weight = exp(-ageDays * Math.log(2.0) / HALF_LIFE_DAYS)
            val t = tallies.getOrPut(r.videoId) { Tally(r, 0.0, r.playedAt) }
            t.score += weight
            if (r.playedAt > t.last) t.last = r.playedAt
        }
        return tallies.values
            .sortedWith(compareByDescending<Tally> { it.score }.thenByDescending { it.last })
            .take(limit)
            .map { ShelfItem(it.record.title, it.record.artist, it.record.thumbnailUrl, it.record.videoId, null, explicit = it.record.explicit == true) }
    }

    /** YouTube Music's own Speed dial shelf among Home's, if it sent one. */
    fun isSpeedDial(title: String): Boolean = title.trim().equals(TITLE, ignoreCase = true)
}
