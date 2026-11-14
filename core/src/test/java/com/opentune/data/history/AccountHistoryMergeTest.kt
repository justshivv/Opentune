package com.opentune.data.history

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountHistoryMergeTest {
    private fun song(id: String) = Song(id, "Song $id", "Artist", null, durationText = "3:00")

    @Test fun bringsInWhatWasPlayedElsewhereOnce() {
        val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 20) }.timeInMillis
        // "a" was played here today (and so is in the account's history too).
        val here = listOf(PlayRecord("a", "Song a", "Artist", playedAt = now - 3_600_000, listenedMs = 180_000))
        val sections = listOf("Today" to listOf(song("b"), song("a")), "Yesterday" to listOf(song("c")), "October" to listOf(song("d")))
        val added = History.accountListens(here, sections, now)
        assertEquals(listOf("b", "c"), added.map { it.videoId })
        assertTrue(added.all { it.fromAccount && it.playedAt < now })
        // Today's comes after yesterday's.
        assertTrue(added[0].playedAt > added[1].playedAt)
        // Merged once, a second read adds nothing.
        assertEquals(emptyList<PlayRecord>(), History.accountListens(here + added, sections, now))
    }
}
