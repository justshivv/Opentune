package com.opentune.data.history

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaylistTest {
    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(day: Int, hour: Int) = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, 0) }.timeInMillis
    private fun play(id: String, artist: String, t: Long) = PlayRecord(id, "Song $id", artist, "https://img/$id", null, null, t, 200_000)
    private val genres = mapOf("Diljit Dosanjh" to "Punjabi", "Divine" to "Hip-Hop", "Anuv Jain" to "Indian Pop")

    /** Nights are Punjabi and hip-hop; mornings are Anuv Jain. October 2026: the 2nd is a Friday. */
    private fun history() = buildList {
        for (d in 1..28) {
            (1..6).forEach { add(play("dd$it", "Diljit Dosanjh", at(d, 22) + it * 60_000L)) }
            (1..4).forEach { add(play("dv$it", "Divine", at(d, 23) + it * 60_000L)) }
            (1..9).forEach { add(play("aj$it", "Anuv Jain", at(d, 9) + it * 60_000L)) }
        }
    }

    @Test fun namedFromItsGenresAndWhen() {
        val night = Daylist.build(history(), { genres[it] }, at(30, 22), zone, locale = Locale.ENGLISH)!!
        assertEquals("Punjabi and Hip-Hop, Friday night", night.title)
        assertTrue(night.songs.all { it.artist == "Diljit Dosanjh" || it.artist == "Divine" })
        val morning = Daylist.build(history(), { genres[it] }, at(30, 9), zone, locale = Locale.ENGLISH)!!
        assertEquals("Indian Pop, Friday morning", morning.title)
        assertNotEquals(night.songs, morning.songs)
    }

    @Test fun lateNightBelongsToTheEveningBefore() {
        // 1am on Saturday the 31st is still Friday night.
        val late = (1..9).map { play("l$it", "Divine", at(20, 1) + it * 60_000L) }
        val list = Daylist.build(late, { genres[it] }, at(31, 1), zone, locale = Locale.ENGLISH)!!
        assertEquals("Hip-Hop, Friday late night", list.title)
    }

    @Test fun nothingWithoutEnoughForThisTimeOfDay() {
        assertNull(Daylist.build(history(), { genres[it] }, at(30, 14), zone))
    }
}
