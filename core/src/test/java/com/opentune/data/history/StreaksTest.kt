package com.opentune.data.history

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreaksTest {
    private val zone = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(day: Int, hour: Int) = Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, 0) }.timeInMillis
    private fun play(id: String, artist: String, t: Long, ms: Long = 200_000) = PlayRecord(id, "Song", artist, null, null, null, t, ms)

    @Test fun daysInARowUpToTodayOrYesterday() {
        val plays = (10..15).map { play("s$it", "A", at(it, 20)) }
        assertEquals(6, Streaks.dayStreak(plays, at(15, 23), zone))
        // Not yet today: the streak up to yesterday still stands.
        assertEquals(6, Streaks.dayStreak(plays, at(16, 8), zone))
        // A day missed ends it.
        assertEquals(0, Streaks.dayStreak(plays, at(17, 8), zone))
    }

    @Test fun skippedSongsDontKeepAStreak() {
        val plays = (10..14).map { play("s$it", "A", at(it, 20)) } + play("x", "A", at(15, 20), ms = 5_000)
        val badges = Streaks.badges(plays, at(15, 23), zone)
        assertEquals(Streaks.Badge.DayStreak(5), badges.first())
    }

    @Test fun newArtistsAndHoursThisWeek() {
        val old = (1..30).map { play("o$it", "Old Artist", at(1, 10) + it * 60_000L) }
        // Ten hours of Arijit: nine hours before this week, the tenth hour in it.
        val arijit = (1..9).map { play("a$it", "Arijit Singh", at(2, 10), ms = 3_600_000) } + play("a10", "Arijit Singh", at(28, 10), ms = 3_600_000)
        val fresh = listOf(play("n1", "New One", at(27, 10)), play("n2", "New Two", at(28, 11)))
        val badges = Streaks.badges(old + arijit + fresh, at(29, 12), zone)
        assertTrue(Streaks.Badge.NewArtists(2) in badges)
        assertTrue(Streaks.Badge.ArtistHours("Arijit Singh", 10) in badges)
    }

    @Test fun aNewInstallHasNoNewArtistsBadge() {
        val plays = (1..5).map { play("s$it", "Artist $it", at(28, 10 + it)) }
        assertTrue(Streaks.badges(plays, at(28, 20), zone).none { it is Streaks.Badge.NewArtists })
    }
}
