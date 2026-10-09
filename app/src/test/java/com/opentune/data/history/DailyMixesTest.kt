package com.opentune.data.history

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMixesTest {
    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(day: Int, hour: Int) = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, 0) }.timeInMillis
    private fun play(id: String, artist: String, t: Long) = PlayRecord(id, "Song $id", artist, "https://img/$id", null, null, t, 200_000)

    /** A month of evenings: mostly Anuv Jain with Prateek Kuhad around him, and some Arijit Singh in the mornings. */
    private fun history(): List<PlayRecord> = buildList {
        for (d in 1..28) {
            val evening = at(d, 21)
            listOf("a1", "a2", "a3", "p1", "a4", "p2", "a5", "p3").forEachIndexed { i, id ->
                add(play(id, if (id.startsWith("a")) "Anuv Jain" else "Prateek Kuhad", evening + i * 4 * 60_000L))
            }
            val morning = at(d, 8)
            listOf("r1", "r2", "r3", "r4").forEachIndexed { i, id -> add(play(id, "Arijit Singh", morning + i * 4 * 60_000L)) }
        }
    }

    @Test fun buildsMixesFromListening() {
        val now = at(29, 22)
        val mixes = DailyMixes.build(history(), now, zone)
        val byId = mixes.associateBy { it.id }
        val first = byId.getValue("artist0")
        assertEquals("Daily Mix 1", first.title)
        assertTrue(first.subtitle, first.subtitle.startsWith("Anuv Jain"))
        assertTrue("Prateek Kuhad comes along: ${first.subtitle}", "Prateek Kuhad" in first.subtitle)
        // Anuv's songs and the ones played around them, never the morning ones.
        assertTrue(first.songs.none { it.videoId.startsWith("r") })
        assertEquals(first.songs.size, first.songs.distinctBy { it.videoId }.size)
        // At 7 pm it's the evening mix; at 10 pm there's nothing played that late, so it's still the evening's.
        assertEquals("Evening mix", DailyMixes.build(history(), at(29, 19), zone).first { it.id == "time" }.title)
        assertEquals("Evening mix", byId.getValue("time").title)
        assertTrue(byId.getValue("time").songs.none { it.videoId.startsWith("r") })
        assertTrue(byId.containsKey("repeat"))
    }

    @Test fun sameAllDayNewTomorrow() {
        val h = history()
        val morning = DailyMixes.build(h, at(29, 9), zone).first { it.id == "artist0" }.songs.map { it.videoId }
        val evening = DailyMixes.build(h, at(29, 20), zone).first { it.id == "artist0" }.songs.map { it.videoId }
        val tomorrow = DailyMixes.build(h, at(30, 9), zone).first { it.id == "artist0" }.songs.map { it.videoId }
        assertEquals(morning, evening)
        assertEquals(morning.toSet(), tomorrow.toSet())
    }

    @Test fun nothingFromALittleListening() {
        assertEquals(emptyList<DailyMixes.Mix>(), DailyMixes.build(listOf(play("x", "A", at(1, 9))), at(2, 9), zone))
    }
}
