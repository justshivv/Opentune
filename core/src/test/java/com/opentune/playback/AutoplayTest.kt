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
    fun startsFetchingWithThreeTracksLeftInPlayOrder() {
        assertFalse(Autoplay.shouldExtend(remaining = 4, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 3, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 2, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 1, queueSize = 5))
        assertTrue(Autoplay.shouldExtend(remaining = 0, queueSize = 5))
    }

    @Test fun readySecondaryRadioDoesNotWaitForStalledPrimary() = kotlinx.coroutines.runBlocking {
        val stalled = kotlinx.coroutines.CompletableDeferred<List<Song>>()
        val ready = kotlinx.coroutines.CompletableDeferred(listOf(song("next")))
        val result = kotlinx.coroutines.withTimeout(1_000) {
            Autoplay.readyRadios(listOf(stalled, ready), { true }, graceMs = 5)
        }
        assertEquals(listOf(emptyList(), listOf(song("next"))), result)
        assertTrue(stalled.isCancelled)
    }

    @Test fun duplicateOnlyResultDoesNotDisplaceUsableRecommendation() = kotlinx.coroutines.runBlocking {
        val duplicate = kotlinx.coroutines.CompletableDeferred(listOf(song("queued")))
        val ready = kotlinx.coroutines.CompletableDeferred(listOf(song("new")))
        val result = Autoplay.readyRadios(listOf(duplicate, ready), { it.videoId != "queued" }, graceMs = 5)
        assertEquals(listOf("new"), Autoplay.newTracks(setOf("queued"), Autoplay.blend(result, emptyList())).map { it.videoId })
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
