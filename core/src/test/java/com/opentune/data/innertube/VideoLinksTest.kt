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
        assertTrue(link.inOnePiece())
    }

    @Test fun aStretchLeftOutPartwayTakesTwoPiecesButAnIntroOne() {
        // Believer's scene at 2:49: the picture would be in two pieces.
        assertFalse(VideoLinks.check(204_000, "7wtfhZwyrcc", 217_000, segments)!!.inOnePiece())
        // Cradles' second and a bit before the song starts: still one piece.
        val intro = listOf(SponsorBlock.Segment(0, 1_125, "music_offtopic", "intro", 218_661))
        assertTrue(VideoLinks.check(217_000, "KBtk5FUeJbk", 218_661, intro)!!.inOnePiece())
    }

    // Titles as YouTube gives them, not as YouTube Music's rows tidy them.
    @Test fun aLyricVideoAFanEditOrASubtitledCopyIsNotTheMusicVideo() {
        assertTrue(VideoLinks.notTheVideo("hideaway", "Marino - hideaway (Official Lyric Video)"))
        assertTrue(VideoLinks.notTheVideo("Worst Enemy", "Marino - Worst Enemy [Lyrics x AMV]"))
        assertTrue(VideoLinks.notTheVideo("What's the Rush?", "Chandol - What's the Rush? (Thai Sub)"))
        assertTrue(VideoLinks.notTheVideo("Check Up", "chandol-Check Up (music video unofficial)"))
        assertFalse(VideoLinks.notTheVideo("Check Up", "chandol - CHECK UP"))
        assertFalse(VideoLinks.notTheVideo("Believer", "Imagine Dragons - Believer (Official Music Video)"))
    }

    @Test fun theArtistsOwnChannelIsTheirsAndAFansIsNot() {
        val checkUp = Song(videoId = "ck8iEAXyHDc", title = "Check Up", artist = "chandol", durationText = "3:00", thumbnailUrl = null)
        assertTrue(VideoLinks.byTheArtist(checkUp, "chandol"))
        assertTrue(VideoLinks.byTheArtist(track, "ImagineDragonsVEVO"))
        assertTrue(VideoLinks.byTheArtist(track.copy(artist = "Imagine Dragons, Lil Wayne"), "Lil Wayne"))
        assertFalse(VideoLinks.byTheArtist(checkUp, "ChandolCentral"))
        assertFalse(VideoLinks.byTheArtist(checkUp, "Ryder Goodman"))
        assertFalse(VideoLinks.byTheArtist(checkUp, "VEVO"))
    }
}
