package com.opentune.data.library

import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.history.PlayRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun theArtistRuleIgnoresCaseAndAccents() {
        val mixed = facts.copy(history = listOf(
            play("x", 1, artist = "Beyoncé"), play("y", 1, artist = "Sigur Rós, Jónsi"), play("z", 1, artist = "दिलजीत दोसांझ"),
        ))
        fun by(artist: String) = SmartPlaylists.songs(SmartRules(artist = artist, limit = null), mixed, now).map { it.videoId }
        assertEquals(listOf("x"), by("beyonce"))
        assertEquals(listOf("x"), by("BEYONCÉ"))
        assertEquals(listOf("y"), by("  sigur ros "))
        assertEquals(listOf("y"), by("JONSI"))
        // Devanagari's vowel signs are part of the word, not accents: the name still has to be spelt.
        assertEquals(listOf("z"), by("दिलजीत"))
        assertEquals(emptyList<String>(), by("दलजत"))
    }

    @Test fun titlesAndArtistsSortAsInTheAlphabetNotByCodePoint() {
        val songs = listOf("Zebra", "éclair", "apple", "Banana", "Eagle").mapIndexed { i, t ->
            PlayRecord("t$i", t, if (i % 2 == 0) "Édith" else "adele", playedAt = now - day)
        }
        val f = facts.copy(history = songs, liked = emptyList(), downloads = emptyMap())
        fun titles(sort: SmartSort) = SmartPlaylists.songs(SmartRules(sort = sort, limit = null), f, now).map { it.title }
        assertEquals(listOf("apple", "Banana", "Eagle", "éclair", "Zebra"), titles(SmartSort.TITLE))
        // Artist first (adele before Édith, whatever the case or accent), then title.
        assertEquals(listOf("Banana", "éclair", "apple", "Eagle", "Zebra"), titles(SmartSort.ARTIST))
    }

    @Test fun aShuffleTurnsOverAtLocalMidnight() {
        val india = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        val many = facts.copy(history = (1..30).map { play("s$it", 1) })
        fun order(at: Long) = SmartPlaylists.songs(SmartRules(sort = SmartSort.SHUFFLE, limit = null), many, at, seed = 7, zone = india).map { it.videoId }
        val localMidnight = now - (5 * 60 + 30) * 60_000L // 00:00 in India on the day `now` starts in UTC
        // 04:00 and 07:00 in India are either side of UTC's midnight, but the same day there.
        assertEquals(order(localMidnight + 4 * 3_600_000L), order(localMidnight + 7 * 3_600_000L))
        assertEquals(order(localMidnight + 1), order(localMidnight + day - 1))
        // The next day there, a new order.
        assertTrue(order(localMidnight + day - 1) != order(localMidnight + day))
    }

    @Test fun playedWithinAndNotPlayedForOverTheSameDaysMatchNothing() {
        val f = facts.copy(history = history + play("m", 60))
        val impossible = SmartRules(playedWithinDays = 30, notPlayedForDays = 90, limit = null)
        assertTrue(impossible.contradictory)
        assertTrue(SmartPlaylists.songs(impossible, f, now).isEmpty())
        assertTrue(SmartRules(playedWithinDays = 30, notPlayedForDays = 30).contradictory)
        // Played in the last three months but not the last month: m, played two months ago.
        val between = SmartRules(playedWithinDays = 90, notPlayedForDays = 30, limit = null)
        assertFalse(between.contradictory)
        assertEquals(listOf("m"), SmartPlaylists.songs(between, f, now).map { it.videoId })
        assertFalse(SmartRules().contradictory)
        SmartPlaylists.presets.forEach { (_, rules) -> assertFalse(rules.contradictory) }
    }

    @Test fun limitsCutAfterSortingAndAZeroOrNoLimitKeepsAll() {
        assertEquals(listOf("a"), ids(SmartRules(sort = SmartSort.MOST_PLAYED, limit = 1)))
        assertEquals(listOf("a", "b"), ids(SmartRules(sort = SmartSort.TITLE, limit = 2)))
        assertEquals(6, ids(SmartRules(limit = null)).size)
        assertEquals(6, ids(SmartRules(limit = 0)).size)
    }

    @Test fun likedAndDownloadedOnlyNarrowAnySource() {
        assertEquals(setOf("b", "d"), ids(SmartRules(likedOnly = true, limit = null)).toSet())
        // Liked and played: b only.
        assertEquals(listOf("b"), ids(SmartRules(SmartSource.HISTORY, likedOnly = true, limit = null)))
        assertEquals(setOf("e", "f"), ids(SmartRules(downloadedOnly = true, limit = null)).toSet())
        // Lossless needs a finished download, wherever the songs come from.
        assertEquals(listOf("e"), ids(SmartRules(losslessOnly = true, limit = null)))
    }

    @Test fun aHugeLibraryIsWorkedOutQuicklyAndInFull() {
        val plays = (0 until 10_000).map { i -> play("h${i % 4_000}", i % 400) }
        val liked = (0 until 5_000).map { ref("l$it") }
        val big = SmartPlaylists.Facts(plays, liked, emptyMap(), listOf(LocalPlaylist("p", "P", (0 until 3_000).map { ref("p$it") })))
        val started = System.nanoTime()
        val all = SmartPlaylists.songs(SmartRules(limit = null, sort = SmartSort.TITLE), big, now)
        assertEquals(4_000 + 5_000 + 3_000, all.size)
        assertEquals(all.size, all.map { it.videoId }.toSet().size)
        assertEquals(50, SmartPlaylists.songs(SmartRules(), big, now).size)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun everyPresetRunsOnEmptyFacts() {
        val empty = SmartPlaylists.Facts(emptyList(), emptyList(), emptyMap())
        SmartPlaylists.presets.forEach { (_, rules) -> assertTrue(SmartPlaylists.songs(rules, empty, now).isEmpty()) }
    }
}
