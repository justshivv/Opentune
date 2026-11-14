package com.opentune.data.history

import java.util.Calendar
import java.util.TimeZone

/**
 * Small, quiet milestones from this phone's listening: how many days in a row you've listened,
 * new artists found this week, and hours spent with an artist as they cross a round number.
 * Nothing is sent anywhere; it's all read from history.
 */
object Streaks {
    /** A play this short (skipped at once) doesn't keep a streak going. */
    private const val COUNTS_MS = 30_000L
    private const val DAY = 24 * 60 * 60 * 1000L
    private val HOURS = listOf(1, 5, 10, 25, 50, 100, 250, 500)

    sealed class Badge {
        /** Listened on [days] days in a row, up to today or yesterday. */
        data class DayStreak(val days: Int) : Badge()
        /** Artists first heard in the last seven days. */
        data class NewArtists(val count: Int) : Badge()
        /** [hours] with [artist], reached in the last week. */
        data class ArtistHours(val artist: String, val hours: Int) : Badge()
    }

    fun badges(records: List<PlayRecord>, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): List<Badge> {
        val plays = records.filter { it.listenedMs >= COUNTS_MS || it.listenedMs == 0L }
        if (plays.isEmpty()) return emptyList()
        val out = mutableListOf<Badge>()
        dayStreak(plays, nowMs, zone).takeIf { it >= 2 }?.let { out += Badge.DayStreak(it) }

        val firstHeard = HashMap<String, Long>()
        plays.forEach { r ->
            val a = Wrapped.mainArtist(r.artist).takeIf { it.isNotBlank() } ?: return@forEach
            firstHeard[a] = minOf(firstHeard[a] ?: Long.MAX_VALUE, r.playedAt)
        }
        // Only once there's history from before the week: on a new install every artist is new.
        val historyStart = plays.minOf { it.playedAt }
        if (nowMs - historyStart > 14 * DAY) {
            firstHeard.count { it.value >= nowMs - 7 * DAY }.takeIf { it >= 2 }?.let { out += Badge.NewArtists(it) }
        }

        // The latest round number of hours any artist crossed this week, the biggest first.
        val weekAgo = nowMs - 7 * DAY
        plays.groupBy { Wrapped.mainArtist(it.artist) }.filterKeys { it.isNotBlank() }.mapNotNull { (artist, list) ->
            val total = list.sumOf { listened(it) }
            val before = list.filter { it.playedAt < weekAgo }.sumOf { listened(it) }
            HOURS.lastOrNull { h -> total >= h * 3_600_000L && before < h * 3_600_000L }?.let { Badge.ArtistHours(artist, it) }
        }.sortedByDescending { it.hours }.firstOrNull()?.let { out += it }
        return out
    }

    /** Days in a row with a play, counting back from today (or yesterday, if not yet today). */
    fun dayStreak(records: List<PlayRecord>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(zone)
        fun dayOf(t: Long): Int { cal.timeInMillis = t; return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }
        val days = records.mapTo(HashSet()) { dayOf(it.playedAt) }
        var t = nowMs
        if (dayOf(t) !in days) t -= DAY
        var n = 0
        while (dayOf(t) in days) { n++; t -= DAY }
        return n
    }

    private fun listened(r: PlayRecord): Long =
        if (r.listenedMs > 0) r.listenedMs else Wrapped.durationMs(r.durationText) ?: 180_000L
}
