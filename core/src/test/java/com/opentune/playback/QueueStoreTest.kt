package com.opentune.playback

import com.opentune.data.library.SongRef
import com.opentune.data.model.QueueTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QueueStoreTest {

    private fun songs(n: Int) = List(n) { SongRef("id$it", "Song $it", "Artist") }

    @Test
    fun aShortQueueIsKeptWhole() {
        val saved = QueueStore.Saved(songs(50), index = 30, positionMs = 1_000)
        assertSame(saved, QueueStore.trimmed(saved, max = 200))
    }

    @Test
    fun aLongQueueKeepsTheSongPlaying() {
        val saved = QueueStore.Saved(songs(500), index = 300, positionMs = 42_000)
        val kept = QueueStore.trimmed(saved, max = 200)
        assertEquals(200, kept.songs.size)
        // The same song, at the same place, reopens.
        assertEquals("id300", kept.songs[kept.index].videoId)
        assertEquals(42_000, kept.positionMs)
        // A few played songs before it, the rest to come.
        assertEquals("id280", kept.songs.first().videoId)
    }

    @Test
    fun nearTheStartTheQueueIsCutFromTheEnd() {
        val kept = QueueStore.trimmed(QueueStore.Saved(songs(500), index = 5, positionMs = 0), max = 200)
        assertEquals("id0", kept.songs.first().videoId)
        assertEquals(5, kept.index)
    }

    @Test
    fun nearTheEndTheLastSongsAreKept() {
        val kept = QueueStore.trimmed(QueueStore.Saved(songs(500), index = 495, positionMs = 0), max = 200)
        assertEquals("id499", kept.songs.last().videoId)
        assertEquals("id495", kept.songs[kept.index].videoId)
    }

    @Test
    fun eachSongKeepsItsPartOfTheQueue() {
        val tiers = List(500) { if (it == 301) QueueTier.USER else QueueTier.CONTEXT }
        val kept = QueueStore.trimmed(QueueStore.Saved(songs(500), index = 300, positionMs = 0, tiers = tiers), max = 200)
        assertEquals(200, kept.tiers!!.size)
        assertEquals(QueueTier.USER, kept.tiers!![kept.index + 1])
        assertEquals("id301", kept.songs[kept.index + 1].videoId)
    }
}
