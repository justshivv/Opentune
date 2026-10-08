package com.opentune.data.history

import com.opentune.data.model.Song
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A listening history summed up for the Wrapped story: how long, who and
 * what most, when in the day, the busiest day, the longest run of days and
 * the song played most in a single day. Everything comes from [History],
 * on the phone; nothing is sent anywhere.
 */
object Wrapped {
    /** A record with no measured listen counts as this much; most songs run about three minutes. */
    private const val GUESSED_MS = 3 * 60_000L

    data class Entry(val title: String, val subtitle: String, val thumbnailUrl: String?, val plays: Int, val minutes: Long, val song: Song?)

    enum class Clock(val title: String, val line: String) {
        NIGHT("Night owl", "Your music comes alive after dark"),
        MORNING("Early bird", "You start the day with a soundtrack"),
        DAY("Daydreamer", "Your music keeps you company all day"),
        EVENING("Sunset chaser", "Evenings are when you press play"),
    }

    data class Summary(
        val sinceMs: Long,
        val minutes: Long,
        val plays: Int,
        val songs: Int,
        val artists: Int,
        val topSongs: List<Entry>,
        val topArtists: List<Entry>,
        val topAlbum: Entry?,
        /** Minutes listened in each hour of the day, 0 to 23. */
        val hours: List<Long>,
        val peakHour: Int,
        val clock: Clock,
        /** Minutes in each of the last twelve months, oldest first, with each month's short name. */
        val months: List<Pair<String, Long>>,
        val busiestDayMs: Long?,
        val busiestDayMinutes: Long,
        val streakDays: Int,
        /** The song played most times in one day, and how many. */
        val onRepeat: Entry?,
        val first: Entry?,
    ) {
        val isEmpty get() = plays == 0
    }

    fun listenedMs(r: PlayRecord): Long = when {
        r.listenedMs > 0 -> r.listenedMs
        else -> durationMs(r.durationText) ?: GUESSED_MS
    }

    fun summarize(records: List<PlayRecord>, sinceMs: Long, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Summary {
        val window = records.filter { it.playedAt in sinceMs..nowMs }
        val cal = Calendar.getInstance(zone)
        fun dayOf(ms: Long): Long {
            cal.timeInMillis = ms
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        fun minutes(list: List<PlayRecord>) = TimeUnit.MILLISECONDS.toMinutes(list.sumOf(::listenedMs))
        fun entry(list: List<PlayRecord>, title: String, subtitle: String) =
            Entry(title, subtitle, list.first().thumbnailUrl, list.size, minutes(list), list.first().toSong())
        val ranked = compareByDescending<Pair<String, List<PlayRecord>>> { it.second.size }.thenByDescending { it.second.sumOf(::listenedMs) }

        val bySong = window.groupBy { it.videoId }.toList().sortedWith(ranked)
        val byArtist = window.groupBy { mainArtist(it.artist) }.filterKeys { it.isNotBlank() }.toList().sortedWith(ranked)
        val byAlbum = window.filter { !it.albumName.isNullOrBlank() }.groupBy { it.albumName!! }.toList().sortedWith(ranked)

        val hours = LongArray(24)
        for (r in window) {
            cal.timeInMillis = r.playedAt
            hours[cal.get(Calendar.HOUR_OF_DAY)] += listenedMs(r)
        }
        val peak = hours.indices.maxByOrNull { hours[it] } ?: 20
        // The part of the day with the most listening, not just the peak hour.
        val parts = mapOf(
            Clock.NIGHT to (listOf(22, 23) + (0..4)),
            Clock.MORNING to (5..10).toList(),
            Clock.DAY to (11..16).toList(),
            Clock.EVENING to (17..21).toList(),
        )
        val clock = parts.maxByOrNull { (_, hs) -> hs.sumOf { hours[it] } }?.key ?: Clock.EVENING

        val byDay = window.groupBy { dayOf(it.playedAt) }
        val busiest = byDay.maxByOrNull { (_, list) -> list.sumOf(::listenedMs) }

        // The longest run of days in a row with something played.
        val days = byDay.keys.sorted()
        var streak = 0
        var run = 0
        var previous: Long? = null
        for (d in days) {
            run = if (previous != null && isNextDay(previous, d, zone)) run + 1 else 1
            streak = maxOf(streak, run)
            previous = d
        }

        val onRepeat = byDay.values
            .flatMap { list -> list.groupBy { it.videoId }.values }
            .maxWithOrNull(compareBy<List<PlayRecord>> { it.size }.thenBy { -it.first().playedAt })
            ?.takeIf { it.size >= 2 }

        val months = (11 downTo 0).map { back ->
            cal.timeInMillis = nowMs
            cal.add(Calendar.MONTH, -back)
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH)
            val name = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.getDefault()).orEmpty()
            name to minutes(records.filter { r -> cal.timeInMillis = r.playedAt; cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month })
        }

        return Summary(
            sinceMs = sinceMs,
            minutes = minutes(window),
            plays = window.size,
            songs = bySong.size,
            artists = byArtist.size,
            topSongs = bySong.take(5).map { (_, list) -> entry(list, list.first().title, list.first().artist) },
            topArtists = byArtist.take(5).map { (name, list) -> entry(list, name, "${list.distinctBy { it.videoId }.size} songs") },
            topAlbum = byAlbum.firstOrNull()?.let { (name, list) -> entry(list, name, mainArtist(list.first().artist)) },
            hours = hours.map { TimeUnit.MILLISECONDS.toMinutes(it) },
            peakHour = peak,
            clock = clock,
            months = months,
            busiestDayMs = busiest?.key,
            busiestDayMinutes = busiest?.value?.let(::minutes) ?: 0,
            streakDays = streak,
            onRepeat = onRepeat?.let { entry(it, it.first().title, it.first().artist) },
            first = window.minByOrNull { it.playedAt }?.let { entry(listOf(it), it.title, it.artist) },
        )
    }

    private fun isNextDay(a: Long, b: Long, zone: TimeZone): Boolean {
        val c = Calendar.getInstance(zone).apply { timeInMillis = a; add(Calendar.DAY_OF_YEAR, 1) }
        val d = Calendar.getInstance(zone).apply { timeInMillis = b }
        return c.get(Calendar.YEAR) == d.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == d.get(Calendar.DAY_OF_YEAR)
    }

    /** "Arijit Singh, Shreya Ghoshal" counts for Arijit Singh, like Replay. */
    internal fun mainArtist(artist: String) = artist.substringBefore(",").substringBefore(" & ").trim()

    internal fun durationMs(text: String?): Long? =
        text?.split(':')?.map { it.trim().toLongOrNull() ?: return null }?.fold(0L) { acc, n -> acc * 60 + n }?.times(1000)?.takeIf { it > 0 }
}
