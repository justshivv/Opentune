package com.opentune.playback

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)

    @Test
    fun emptyQueueNeverExtends() {
        assertFalse(Autoplay.shouldExtend(currentIndex = 0, queueSize = 0))
    }

    @Test
    fun singleTrackExtendsSoTappingASongStartsRadio() {
        assertTrue(Autoplay.shouldExtend(currentIndex = 0, queueSize = 1))
    }

    @Test
    fun extendsOnSecondToLastAndLastTrack() {
        assertFalse(Autoplay.shouldExtend(currentIndex = 2, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(currentIndex = 3, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(currentIndex = 4, queueSize = 5))
    }

    @Test
    fun newTracksDropsQueuedAndRepeatedIds() {
        val radio = listOf(song("seed"), song("a"), song("b"), song("a"), song("c"))
        val added = Autoplay.newTracks(queuedIds = setOf("seed", "b"), radio = radio)
        assertEquals(listOf("a", "c"), added.map { it.videoId })
    }
}
