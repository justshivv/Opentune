package com.opentune.data.history

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreMixesTest {
    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(day: Int, hour: Int) = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, 0) }.timeInMillis
    private fun play(id: String, artist: String, t: Long) = PlayRecord(id, "Song $id", artist, "https://img/$id", null, null, t, 200_000)

    private val genres = mapOf(
        "Arijit Singh" to "Bollywood", "Shreya Ghoshal" to "Bollywood",
        "Diljit Dosanjh" to "Punjabi", "Karan Aujla" to "Punjabi",
        "Anuv Jain" to "Indian Pop",
    )

    /** Most Bollywood, then Punjabi; a little Indian Pop from one artist only. */
    private fun history(): List<PlayRecord> = buildList {
        for (d in 1..10) {
            (1..6).forEach { add(play("ar$it", "Arijit Singh", at(d, 20) + it * 60_000L)) }
            (1..4).forEach { add(play("sg$it", "Shreya Ghoshal, Sonu Nigam", at(d, 21) + it * 60_000L)) }
            (1..5).forEach { add(play("dd$it", "Diljit Dosanjh", at(d, 9) + it * 60_000L)) }
            (1..4).forEach { add(play("ka$it", "Karan Aujla", at(d, 10) + it * 60_000L)) }
            (1..9).forEach { if (d == 1) add(play("aj$it", "Anuv Jain", at(d, 23) + it * 60_000L)) }
        }
    }

    @Test fun oneMixPerGenreMostPlayedFirst() {
        val mixes = GenreMixes.build(history(), { genres[it] }, at(11, 12), zone)
        assertEquals(listOf("genre:bollywood", "genre:punjabi"), mixes.map { it.id })
        val bollywood = mixes.first()
        assertEquals(10, bollywood.songs.size)
        assertTrue(bollywood.songs.all { it.artist.startsWith("Arijit") || it.artist.startsWith("Shreya") })
        assertEquals("Arijit Singh, Shreya Ghoshal", bollywood.subtitle)
        // One artist alone isn't a genre mix, however many songs.
        assertTrue(mixes.none { it.id == "genre:indian pop" })
    }

    @Test fun sameOrderAllDayNewOrderTheNext() {
        val morning = GenreMixes.build(history(), { genres[it] }, at(11, 8), zone).first().songs
        val evening = GenreMixes.build(history(), { genres[it] }, at(11, 22), zone).first().songs
        assertEquals(morning, evening)
        val tomorrow = (12..20).map { GenreMixes.build(history(), { genres[it] }, at(it, 8), zone).first().songs }
        assertTrue(tomorrow.any { it != morning })
    }

    @Test fun artistsWithoutAGenreAreLeftOut() {
        assertTrue(GenreMixes.build(history(), { null }, at(11, 12), zone).isEmpty())
    }
}
