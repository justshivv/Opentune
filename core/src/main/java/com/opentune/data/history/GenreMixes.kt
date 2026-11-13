package com.opentune.data.history

import com.opentune.data.library.HiddenList
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * Mixes of your own listening, one for each genre you play the most: every artist's plays go
 * under that artist's genre ([genreOf], from [com.opentune.data.metadata.ArtistGenres]), the
 * songs you play most of each genre nearest the front. The order changes each day, like
 * [DailyMixes]; a genre needs a handful of different songs, from more than one artist, to
 * be a mix rather than a second artist mix.
 */
object GenreMixes {
    private const val MIN_SONGS = 8
    private const val MIN_ARTISTS = 2
    private const val MAX_SONGS = 40
    private const val MAX_MIXES = 8

    fun build(
        allRecords: List<PlayRecord>,
        genreOf: (artist: String) -> String?,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        hidden: HiddenList = HiddenList.EMPTY,
    ): List<DailyMixes.Mix> {
        val records = hidden.records(allRecords)
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val byGenre = records.groupBy { r -> Wrapped.mainArtist(r.artist).takeIf { it.isNotBlank() }?.let(genreOf) }
        return byGenre.entries
            .mapNotNull { (genre, plays) -> genre?.let { it to plays } }
            .sortedByDescending { it.second.size }
            .mapNotNull { (genre, plays) ->
                val artists = plays.groupBy { Wrapped.mainArtist(it.artist) }.toList().sortedByDescending { it.second.size }.map { it.first }
                val songs = plays.groupBy { it.videoId }.values.sortedByDescending { it.size }.map { it.first().toSong() }
                if (songs.size < MIN_SONGS || artists.size < MIN_ARTISTS) return@mapNotNull null
                DailyMixes.Mix(
                    "genre:${genre.lowercase()}",
                    com.opentune.i18n.Tr.f("{0} Mix", genre),
                    artists.take(3).joinToString(", "),
                    dailyOrder(songs, today + genre.hashCode()).take(MAX_SONGS),
                )
            }
            .take(MAX_MIXES)
    }

    /** The same all day and different the next, with the favourites kept near the front. */
    private fun dailyOrder(songs: List<com.opentune.data.model.Song>, seed: Int): List<com.opentune.data.model.Song> {
        val r = Random(seed)
        val third = ((songs.size + 2) / 3).coerceAtLeast(1)
        return songs.chunked(third).flatMap { it.shuffled(r) }
    }
}
