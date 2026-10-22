package com.opentune.playback

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)
    private fun by(id: String, artist: String) = Song(videoId = id, title = id, artist = artist, thumbnailUrl = null)

    @Test
    fun emptyQueueNeverExtends() {
        assertFalse(Autoplay.shouldExtend(remaining = 0, queueSize = 0))
    }

    @Test
    fun singleTrackExtendsSoTappingASongStartsRadio() {
        assertTrue(Autoplay.shouldExtend(remaining = 0, queueSize = 1))
    }

    @Test
    fun extendsWithOneOrNoTracksLeftInPlayOrder() {
        assertFalse(Autoplay.shouldExtend(remaining = 2, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 1, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 0, queueSize = 5))
    }

    @Test
    fun newTracksDropsQueuedAndRepeatedIds() {
        val radio = listOf(song("seed"), song("a"), song("b"), song("a"), song("c"))
        val added = Autoplay.newTracks(queuedIds = setOf("seed", "b"), radio = radio)
        assertEquals(listOf("a", "c"), added.map { it.videoId })
    }

    @Test
    fun seedsTakeTheLastSongThenEarlierOnesByOtherArtists() {
        val queue = listOf(by("a1", "A"), by("b1", "B"), by("c1", "C"), by("c2", "C"), by("a2", "A"))
        assertEquals(listOf("a2", "c2", "b1"), Autoplay.seeds(queue) { true }.map { it.videoId })
    }

    @Test
    fun noSeedsWhenTheLastSongHasNoRadio() {
        val queue = listOf(by("a1", "A"), by("local", "B"))
        assertTrue(Autoplay.seeds(queue) { it.videoId != "local" }.isEmpty())
    }

    @Test
    fun blendPutsSongsSeveralRadiosAgreeOnFirst() {
        val lead = listOf(by("x", "X"), by("y", "Y"), by("z", "Z"))
        val other = listOf(by("z", "Z"), by("w", "W"))
        assertEquals("z", Autoplay.blend(listOf(lead, other), emptyList()).first().videoId)
    }

    @Test
    fun blendKeepsOneRadioAsItIs() {
        val lead = listOf(by("x", "X"), by("y", "X"))
        assertEquals(lead, Autoplay.blend(listOf(lead), emptyList()))
    }

    @Test
    fun noArtistTwiceInARowWhenAnotherCanGoBetween() {
        val ranked = listOf(by("a1", "A"), by("a2", "A"), by("b1", "B"), by("a3", "A"))
        assertEquals(listOf("a1", "b1", "a2", "a3"), Autoplay.spreadArtists(ranked).map { it.videoId })
    }
}
