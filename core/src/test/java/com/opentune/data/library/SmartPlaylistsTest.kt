package com.opentune.data.library

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.history.PlayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun play(id: String, daysAgo: Int, artist: String = "Artist $id") =
        PlayRecord(id, "Song $id", artist, playedAt = now - daysAgo * day)
    private fun ref(id: String, artist: String = "Artist $id") = SongRef(id, "Song $id", artist)

    // a: played 4 times this week; b: twice long ago; c: once yesterday; d: liked, never played.
    private val history = listOf(
        play("a", 1), play("c", 1), play("a", 2), play("a", 3), play("a", 5),
        play("b", 200), play("b", 210),
    )
    private val facts = SmartPlaylists.Facts(
        history = history,
        liked = listOf(ref("b"), ref("d")),
        downloads = mapOf(
            "e" to DownloadEntry(ref("e"), DownloadState.DONE, addedAt = now - 2 * day, bits = 24, sampleRate = 96_000),
            "f" to DownloadEntry(ref("f"), DownloadState.DONE, addedAt = now - 400 * day),
            "g" to DownloadEntry(ref("g"), DownloadState.FAILED, addedAt = now - day, bits = 16),
        ),
    )
    private fun ids(rules: SmartRules) = SmartPlaylists.songs(rules, facts, now).map { it.videoId }

    @Test fun onRepeatCountsOnlyRecentPlays() {
        assertEquals(listOf("a"), ids(SmartRules(SmartSource.HISTORY, minPlays = 3, playedWithinDays = 30)))
        // Played at all in the last month, most played first.
        assertEquals(listOf("a", "c"), ids(SmartRules(SmartSource.HISTORY, playedWithinDays = 30)))
    }

    @Test fun forgottenFavouritesAreLikedAndNotPlayedLately() {
        // d was never played, so it has waited longest.
        assertEquals(listOf("d", "b"), ids(SmartRules(SmartSource.LIKED, notPlayedForDays = 90, sort = SmartSort.LONGEST_UNPLAYED)))
    }

    @Test fun newToYouUsesFirstPlayOrDownload() {
        val newest = ids(SmartRules(newWithinDays = 30, sort = SmartSort.NEWEST, limit = null))
        // c first played a day ago, e downloaded two days ago, a first played five days ago.
        assertEquals(listOf("c", "e", "a"), newest)
    }

    @Test fun downloadsMeanFinishedOnesAndLosslessNeedsMeasuredBits() {
        assertEquals(setOf("e", "f"), ids(SmartRules(SmartSource.DOWNLOADS, limit = null)).toSet())
        assertEquals(listOf("e"), ids(SmartRules(SmartSource.DOWNLOADS, losslessOnly = true)))
    }

    @Test fun artistLimitAndSorts() {
        val mixed = facts.copy(history = history + play("h", 4, artist = "AP Dhillon, Gurinder Gill"))
        assertEquals(listOf("h"), SmartPlaylists.songs(SmartRules(artist = "ap dhillon"), mixed, now).map { it.videoId })
        assertEquals(2, ids(SmartRules(limit = 2)).size)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), ids(SmartRules(sort = SmartSort.TITLE, limit = null)))
        val shuffled = ids(SmartRules(sort = SmartSort.SHUFFLE, limit = null))
        assertEquals(shuffled, ids(SmartRules(sort = SmartSort.SHUFFLE, limit = null)))
        assertTrue(shuffled.toSet() == setOf("a", "b", "c", "d", "e", "f"))
    }

    @Test fun everyPresetRunsOnEmptyFacts() {
        val empty = SmartPlaylists.Facts(emptyList(), emptyList(), emptyMap())
        SmartPlaylists.presets.forEach { (_, rules) -> assertTrue(SmartPlaylists.songs(rules, empty, now).isEmpty()) }
    }
}
