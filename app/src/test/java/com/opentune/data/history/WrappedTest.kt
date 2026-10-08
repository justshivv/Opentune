package com.opentune.data.history

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.MARCH, day, hour, 0, 0)
    }.timeInMillis

    private fun play(id: String, artist: String, day: Int, hour: Int, minutes: Long = 3, album: String? = null) =
        PlayRecord(id, "Song $id", artist, "https://img.invalid/$id=w60-h60", "3:00", album, at(day, hour), minutes * 60_000)

    private val records = listOf(
        play("a", "Anuv Jain", 1, 23),
        play("a", "Anuv Jain", 1, 23),
        play("a", "Anuv Jain", 1, 0),
        play("b", "Anuv Jain, Lost Stories", 2, 22, album = "Husn"),
        play("c", "Arijit Singh", 3, 21, album = "Husn"),
        play("d", "The Weeknd", 5, 9, minutes = 10),
    ).sortedByDescending { it.playedAt }

    @Test fun sumsTimeAndCountsWhatWasPlayed() {
        val s = Wrapped.summarize(records, at(1, 0), at(31, 0), utc)
        assertEquals(6, s.plays)
        assertEquals(25, s.minutes)
        assertEquals(4, s.songs)
        assertEquals(3, s.artists)
    }

    @Test fun ranksArtistsBySongsPlayedCountingFeaturesForTheLead() {
        val s = Wrapped.summarize(records, at(1, 0), at(31, 0), utc)
        assertEquals("Anuv Jain", s.topArtists.first().title)
        assertEquals(4, s.topArtists.first().plays)
        assertEquals("Song a", s.topSongs.first().title)
        assertEquals("Husn", s.topAlbum?.title)
    }

    @Test fun readsThePartOfTheDayNotJustThePeakHour() {
        val s = Wrapped.summarize(records, at(1, 0), at(31, 0), utc)
        // One long morning listen makes 9 AM the single biggest hour, but the nights add up to more.
        assertEquals(9, s.peakHour)
        assertEquals(Wrapped.Clock.NIGHT, s.clock)
    }

    @Test fun findsTheStreakTheBusiestDayAndTheSongOnRepeat() {
        val s = Wrapped.summarize(records, at(1, 0), at(31, 0), utc)
        assertEquals(3, s.streakDays)
        assertEquals(at(5, 0), s.busiestDayMs)
        assertEquals("Song a", s.onRepeat?.title)
        assertEquals(3, s.onRepeat?.plays)
        assertEquals("Song a", s.first?.title)
    }

    @Test fun leavesOutWhatCameBeforeThePeriod() {
        val s = Wrapped.summarize(records, at(3, 0), at(31, 0), utc)
        assertEquals(2, s.plays)
        assertNull(s.onRepeat)
        assertEquals(12, s.months.size)
        assertTrue(s.months.last().second >= 25)
    }

    @Test fun anEmptyHistoryIsEmpty() {
        val s = Wrapped.summarize(emptyList(), 0, at(31, 0), utc)
        assertTrue(s.isEmpty)
        assertEquals(0, s.streakDays)
        assertNull(s.first)
    }

    @Test fun guessesTheLengthOfAnUnmeasuredListen() {
        assertEquals(245_000, Wrapped.listenedMs(PlayRecord("x", "t", "a", durationText = "4:05", playedAt = 0)))
        assertEquals(180_000, Wrapped.listenedMs(PlayRecord("x", "t", "a", playedAt = 0)))
    }
}
