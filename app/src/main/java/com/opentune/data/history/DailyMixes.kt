package com.opentune.data.history

import com.opentune.data.model.Song
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Mixes made fresh each day from this phone's own listening, no account or
 * server: one for each of your top artists (their songs and the songs you
 * play around them), one for this time of day, what's on repeat lately, and
 * old favourites you haven't played in a while. The order changes daily;
 * when a mix runs out, autoplay carries on with new songs like it does
 * after anything else.
 */
object DailyMixes {
    data class Mix(
        val id: String,
        val title: String,
        val subtitle: String,
        val songs: List<Song>,
    ) {
        /** Up to four different covers, for the card. */
        val covers: List<String?> get() = songs.mapNotNull { it.thumbnailUrl }.distinct().take(4)
    }

    private const val MIN_SONGS = 6
    private const val MAX_SONGS = 30
    private const val DAY = 24 * 60 * 60 * 1000L
    /** Plays this close together count as one sitting. */
    private const val SITTING = 30 * 60 * 1000L

    fun build(records: List<PlayRecord>, nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): List<Mix> {
        if (records.size < MIN_SONGS) return emptyList()
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val sorted = records.sortedBy { it.playedAt }
        val mixes = mutableListOf<Mix>()

        // Sittings: runs of plays with short gaps, to find what goes with what.
        val sittings = mutableListOf<MutableList<PlayRecord>>()
        for (r in sorted) {
            val last = sittings.lastOrNull()
            if (last != null && r.playedAt - last.last().playedAt <= SITTING) last += r else sittings += mutableListOf(r)
        }

        // One mix for each of the top three artists.
        val byArtist = records.groupBy { Wrapped.mainArtist(it.artist) }.filterKeys { it.isNotBlank() }
            .toList().sortedByDescending { it.second.size }.take(3)
        byArtist.forEachIndexed { i, (artist, plays) ->
            val near = sittings.filter { s -> s.any { Wrapped.mainArtist(it.artist) == artist } }.flatten()
            val own = weighted(plays)
            val around = weighted(near.filter { Wrapped.mainArtist(it.artist) != artist })
            val songs = interleave(shuffle(own, today + i), shuffle(around, today + i + 7), ownEvery = 2)
            val others = around.map { Wrapped.mainArtist(it.artist) }.distinct().take(2)
            mix("artist$i", "Daily Mix ${i + 1}", listOf(artist).plus(others).joinToString(", "), songs)?.let(mixes::add)
        }

        // What you play at this time of day.
        // If there's too little for now, the part of the day just before.
        val now = partOf(cal.get(Calendar.HOUR_OF_DAY))
        val partOfPlay = records.associateWith { r ->
            cal.timeInMillis = r.playedAt
            partOf(cal.get(Calendar.HOUR_OF_DAY))
        }
        listOf(now, Part.entries[(now.ordinal + Part.entries.size - 1) % Part.entries.size]).firstNotNullOfOrNull { part ->
            mix("time", part.title, part.line, shuffle(weighted(records.filter { partOfPlay[it] == part }), today + 31))
        }?.let(mixes::add)

        // On repeat: the most played of the last month.
        val month = records.filter { it.playedAt >= nowMs - 30 * DAY }
        val repeat = month.groupBy { it.videoId }.values.filter { it.size >= 2 }.sortedByDescending { it.size }.map { it.first().toSong() }
        mix("repeat", "On repeat", "What you keep coming back to", repeat)?.let(mixes::add)

        // Rediscover: played a lot before, not at all lately.
        val recent = month.mapTo(HashSet()) { it.videoId }
        val older = records.filter { it.playedAt < nowMs - 45 * DAY && it.videoId !in recent }
        val rediscover = older.groupBy { it.videoId }.values.filter { it.size >= 2 }.sortedByDescending { it.size }.map { it.first().toSong() }
        mix("rediscover", "Rediscover", "Old favourites you haven't played lately", shuffle(rediscover, today + 53))?.let(mixes::add)

        return mixes
    }

    private enum class Part(val title: String, val line: String) {
        MORNING("Morning mix", "What you play to start the day"),
        AFTERNOON("Afternoon mix", "Your songs for the middle of the day"),
        EVENING("Evening mix", "What you play as the day winds down"),
        NIGHT("Late night mix", "Your after-dark songs"),
    }

    private fun partOf(hour: Int) = when (hour) {
        in 5..11 -> Part.MORNING
        in 12..16 -> Part.AFTERNOON
        in 17..21 -> Part.EVENING
        else -> Part.NIGHT
    }

    private fun mix(id: String, title: String, subtitle: String, songs: List<Song>): Mix? {
        val distinct = songs.distinctBy { it.videoId }.take(MAX_SONGS)
        return if (distinct.size >= MIN_SONGS) Mix(id, title, subtitle, distinct) else null
    }

    /** Each song once, the more played first. */
    private fun weighted(plays: List<PlayRecord>): List<Song> =
        plays.groupBy { it.videoId }.values.sortedByDescending { it.size }.map { it.first().toSong() }

    /** A shuffle that's the same all day and different the next. */
    private fun shuffle(songs: List<Song>, seed: Int): List<Song> {
        // Keep the favourites near the front: shuffle within thirds.
        val r = Random(seed)
        val third = (songs.size + 2) / 3
        return songs.chunked(third.coerceAtLeast(1)).flatMap { it.shuffled(r) }
    }

    private fun interleave(own: List<Song>, around: List<Song>, ownEvery: Int): List<Song> {
        val out = mutableListOf<Song>()
        val a = own.iterator()
        val b = around.iterator()
        while (a.hasNext() || b.hasNext()) {
            repeat(ownEvery) { if (a.hasNext()) out += a.next() }
            if (b.hasNext()) out += b.next()
        }
        return out
    }
}
