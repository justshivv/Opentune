package com.opentune.data.reco

import com.opentune.data.history.PlayRecord
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TasteModelTest {
    @Before fun fresh() = TasteModel.reset()

    private fun song(id: String, artist: String, title: String = id, video: Boolean = false) = Song(id, title, artist, null, durationText = "3:00", isVideo = video)
    private fun heard(id: String, artist: String, ms: Long) = PlayRecord(id, id, artist, durationText = "3:00", playedAt = 0, listenedMs = ms)

    @Test fun artistsPlayedThroughComeFirstAndSkippedSongsLast() {
        val history = List(20) { heard("f$it", "Favourite", 175_000) } + List(5) { heard("s", "Skipped", 5_000) }
        val taste = TasteModel.Taste(history, emptySet())
        val radio = listOf(song("s", "Skipped"), song("n", "Newcomer"), song("x", "Favourite"))
        val ranked = TasteModel.rank(radio, taste).map { it.videoId }
        assertEquals("x", ranked.first())
        assertEquals("s", ranked.last())
    }

    @Test fun learnsFromWhatIsSkipped() {
        val taste = TasteModel.Taste(emptyList(), emptySet())
        val video = song("v", "Someone", video = true)
        val before = TasteModel.score(video, 0.5, taste)
        repeat(40) { TasteModel.learn(video, playedThrough = false, taste = taste) }
        assertTrue(TasteModel.score(video, 0.5, taste) < before)
        assertTrue(TasteModel.current()[6] < -0.5)
    }
}
