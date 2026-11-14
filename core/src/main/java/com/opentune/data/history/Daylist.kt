package com.opentune.data.history

import com.opentune.data.library.HiddenList
import com.opentune.data.model.Song
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * One mix that changes through the day: what you play at this part of the day, more of what
 * you play on this weekday, more of the last two months than of long ago. Named from what's
 * in it, its two main genres and when, "Punjabi and Hip-Hop, Friday night", so it reads
 * differently each time it turns over. Built from this phone's own listening only.
 */
object Daylist {
    const val ID = "daylist"
    private const val MIN_SONGS = 8
    private const val MAX_SONGS = 40
    private const val DAY = 24 * 60 * 60 * 1000L

    enum class Part(val label: String, val hours: IntRange) {
        EARLY("early morning", 5..7),
        MORNING("morning", 8..11),
        AFTERNOON("afternoon", 12..15),
        EVENING("evening", 16..19),
        NIGHT("night", 20..23),
        LATE("late night", 0..4),
        ;
        companion object {
            fun of(hour: Int): Part = entries.first { hour in it.hours }
        }
    }

    /** The part of the day [nowMs] falls in, and so when the daylist next turns over. */
    fun partAt(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Part =
        Part.of(Calendar.getInstance(zone).apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY))

    fun build(
        allRecords: List<PlayRecord>,
        genreOf: (artist: String) -> String?,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        hidden: HiddenList = HiddenList.EMPTY,
        locale: Locale = Locale.getDefault(),
    ): DailyMixes.Mix? {
        val records = hidden.records(allRecords)
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val part = Part.of(cal.get(Calendar.HOUR_OF_DAY))
        val weekday = cal.get(Calendar.DAY_OF_WEEK)
        val dayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        // Late night belongs to the evening before it: Saturday 1am is still Friday night.
        val nightOf = if (part == Part.LATE) (weekday + 5) % 7 + 1 else weekday

        // Each play here weighs by how like now it is: the same weekday counts twice, and plays
        // from the last two months count more than older ones.
        val weight = HashMap<String, Double>()
        val first = HashMap<String, PlayRecord>()
        for (r in records) {
            cal.timeInMillis = r.playedAt
            val p = Part.of(cal.get(Calendar.HOUR_OF_DAY))
            if (p != part) continue
            val d = cal.get(Calendar.DAY_OF_WEEK).let { if (p == Part.LATE) (it + 5) % 7 + 1 else it }
            val w = (if (d == nightOf) 2.0 else 1.0) * (if (nowMs - r.playedAt <= 60 * DAY) 1.5 else 1.0)
            weight[r.videoId] = (weight[r.videoId] ?: 0.0) + w
            first.putIfAbsent(r.videoId, r)
        }
        if (weight.size < MIN_SONGS) return null
        val ranked = weight.entries.sortedByDescending { it.value }.map { first.getValue(it.key) }

        // Its name: the two genres with the most weight, then when.
        val genres = HashMap<String, Double>()
        ranked.forEach { r ->
            Wrapped.mainArtist(r.artist).takeIf { it.isNotBlank() }?.let(genreOf)?.let { g -> genres[g] = (genres[g] ?: 0.0) + weight.getValue(r.videoId) }
        }
        val top = genres.entries.sortedByDescending { it.value }.map { it.key }.take(2)
        val dayName = Calendar.getInstance(zone).apply { set(Calendar.DAY_OF_WEEK, nightOf) }
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale) ?: ""
        val time = "$dayName ${com.opentune.i18n.Tr.t(part.label)}".trim()
        val title = when (top.size) {
            0 -> time.replaceFirstChar { it.titlecase(locale) }
            1 -> com.opentune.i18n.Tr.f("{0}, {1}", top[0], time)
            else -> com.opentune.i18n.Tr.f("{0} and {1}, {2}", top[0], top[1], time)
        }
        val songs = turned(ranked.map { it.toSong() }, dayKey * 10 + part.ordinal).take(MAX_SONGS)
        return DailyMixes.Mix(ID, title, "Your daylist · it changes through the day", songs)
    }

    /** Favourites near the front, a different order each time the daylist turns over. */
    private fun turned(songs: List<Song>, seed: Int): List<Song> {
        val r = Random(seed)
        val third = ((songs.size + 2) / 3).coerceAtLeast(1)
        return songs.chunked(third).flatMap { it.shuffled(r) }
    }
}
