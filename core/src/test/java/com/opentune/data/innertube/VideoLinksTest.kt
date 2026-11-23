package com.opentune.data.innertube

import com.opentune.data.lyrics.LyricsGaps
import com.opentune.data.model.Song
import com.opentune.data.sponsorblock.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLinksTest {
    private val track = Song(videoId = "Kx7B-XvmFtE", title = "Believer", artist = "Imagine Dragons", durationText = "3:24", thumbnailUrl = null)
    private fun video(title: String, artist: String = "Imagine Dragons", id: String = "7wtfhZwyrcc") =
        Song(videoId = id, title = title, artist = artist, durationText = "3:37", thumbnailUrl = null, isVideo = true)

    // Believer's music video: a scene with no music at 2:49, and credits at the end.
    private val segments = listOf(
        SponsorBlock.Segment(169_060, 179_149, "music_offtopic", "gap", 216_561),
        SponsorBlock.Segment(212_900, 216_561, "music_offtopic", "end", 0),
    )

    @Test fun theSongsOwnMusicVideoIsFound() {
        assertTrue(VideoLinks.sameSong(track, video("Imagine Dragons - Believer (Official Music Video)")))
        assertTrue(VideoLinks.sameSong(track, video("Believer")))
    }

    @Test fun otherVideosOfTheSongAreNot() {
        assertFalse(VideoLinks.sameSong(track, video("Imagine Dragons - Believer (Lyrics)")))
        assertFalse(VideoLinks.sameSong(track, video("Believer (Official Audio)")))
        assertFalse(VideoLinks.sameSong(track, video("Believer - Imagine Dragons (Cover)", artist = "Someone")))
        assertFalse(VideoLinks.sameSong(track, video("Believer", artist = "Someone Else")))
        assertFalse(VideoLinks.sameSong(track, video("Thunder")))
    }

    @Test fun aVideoWhoseSongIsTheTracksLengthIsLinked() {
        val link = VideoLinks.check(204_000, "7wtfhZwyrcc", 217_000, segments)
        assertNotNull(link)
        assertEquals(216_561L, link!!.videoMs)
        assertEquals(listOf(LyricsGaps.Gap(169_060, 179_149), LyricsGaps.Gap(212_900, 216_561)), link.gaps)
    }

    @Test fun aLongerCutIsNot() {
        assertNull(VideoLinks.check(204_000, "7wtfhZwyrcc", 217_000, emptyList()))
        assertNull(VideoLinks.check(0, "7wtfhZwyrcc", 217_000, segments))
    }

    @Test fun thePictureLeavesOutTheSceneButKeepsTheCredits() {
        val link = VideoLinks.check(204_000, "7wtfhZwyrcc", 217_000, segments)!!
        assertEquals(listOf(0L to 169_060L, 179_149L to 216_561L), link.pieces())
        assertTrue(link.pictureMs() >= link.songMs)
    }

    @Test fun aVideoWithNothingLeftOutIsOnePiece() {
        val link = VideoLinks.check(204_000, "x", 205_000, emptyList())!!
        assertEquals(listOf(0L to 205_000L), link.pieces())
    }
}
