package com.opentune.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDialTest {
    @Test fun localListeningRefreshesAccountSpeedDialWithoutDuplicateCards() {
        val old = com.opentune.data.model.ShelfItem("Old", "Artist", null, "old", null)
        val fresh = old.copy(title = "Fresh", videoId = "fresh")
        assertEquals(listOf("fresh", "old"), SpeedDial.personalized(listOf(fresh), listOf(old, fresh)).map { it.videoId })
    }

    @Test fun artistAggregatesRetainARepresentativeSongAndItsBrowseId() {
        val record = PlayRecord("song", "Track", "Artist", playedAt = 100, listenedMs = 30_000, artistId = "UC_personal")
        val artist = History.replay(listOf(record), 0).topArtists.single()
        assertEquals("UC_personal", artist.song?.artistId)
    }
    private val day = 86_400_000L
    private val now = 1_000 * day
    private fun play(id: String, daysAgo: Double, listened: Long = 120_000) =
        PlayRecord(id, "Song $id", "Artist", playedAt = now - (daysAgo * day).toLong(), listenedMs = listened)

    @Test fun mostPlayedComeFirst() {
        val records = listOf(play("a", 1.0), play("b", 1.0), play("b", 2.0), play("b", 3.0), play("a", 4.0))
        assertEquals(listOf("b", "a"), SpeedDial.fromHistory(records, now).map { it.videoId })
    }

    @Test fun recentPlaysCountForMore() {
        // Three plays two months ago lose to two this week.
        val records = listOf(play("new", 1.0), play("new", 2.0), play("old", 60.0), play("old", 61.0), play("old", 62.0))
        assertEquals("new", SpeedDial.fromHistory(records, now).first().videoId)
    }

    @Test fun skipsDontCount() {
        val records = listOf(play("kept", 1.0), play("skipped", 1.0, listened = 3_000), play("skipped", 1.5, listened = 2_000))
        assertEquals(listOf("kept"), SpeedDial.fromHistory(records, now).map { it.videoId })
    }

    @Test fun albumsAndPlaylistsPlayedOftenJoinTheSongs() {
        val records = listOf(play("a", 1.0), play("a", 2.0), play("b", 1.0))
        val album = CollectionPlay("MPREb_album", "Album", "Artist", playedAt = listOf(now - day, now - 2 * day))
        val items = SpeedDial.fromHistory(records, now, collections = listOf(album))
        assertEquals("MPREb_album", items.first().browseId)
        assertEquals(listOf("a", "b"), items.drop(1).map { it.videoId })
    }

    @Test fun recognisesYouTubeMusicsShelf() {
        assertTrue(SpeedDial.isSpeedDial("Speed dial"))
        assertTrue(SpeedDial.isSpeedDial(" speed Dial "))
    }
}
