package com.opentune.data.covers

import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackArtworkTest {
    @Test fun laterMetadataCannotReplaceThePlayingCover() {
        var preferred: String? = null
        val art = PlaybackArtwork { preferred }
        val song = Song("one", "Song", "Artist", "https://images.test/cover=w120-h120")
        assertEquals("https://images.test/cover=w1200-h1200", art.choose(song))
        preferred = "https://images.test/other"
        assertEquals("https://images.test/cover=w1200-h1200", art.choose(song.copy(thumbnailUrl = "https://images.test/new")))
        art.choose(song.copy(videoId = "two"))
        assertEquals(preferred, art.choose(song))
    }

    @Test fun aSharperCoverForTheSameTrackReplacesTheFirstOnce() {
        val art = PlaybackArtwork { null }
        val song = Song("one", "Song", "Artist", "https://images.test/cover=w120-h120")
        assertEquals("https://images.test/cover=w1200-h1200", art.choose(song))
        val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music/x.jpg/100x100bb.jpg"
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/x.jpg/1200x1200bb.jpg", art.choose(song, apple))
        // Later metadata still can't move it.
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/x.jpg/1200x1200bb.jpg", art.choose(song.copy(thumbnailUrl = "https://images.test/new")))
        // A downloaded song keeps the cover saved with it.
        val saved = PlaybackArtwork { "file:/saved-cover.jpg" }
        assertEquals("file:/saved-cover.jpg", saved.choose(song, apple))
    }

    @Test fun eachImageHostIsResizedItsOwnWay() {
        assertEquals("https://is1-ssl.mzstatic.com/a.jpg/3000x3000bb.jpg", "https://is1-ssl.mzstatic.com/a.jpg/100x100bb.jpg".artworkAt(4000))
        assertEquals("https://cdn-images.dzcdn.net/images/cover/a/160x160-000000-80-0-0.jpg", "https://cdn-images.dzcdn.net/images/cover/a/1000x1000-000000-80-0-0.jpg".artworkAt(160))
        assertEquals("https://cdn-images.dzcdn.net/images/cover/a/1200x1200-000000-80-0-0.jpg", "https://cdn-images.dzcdn.net/images/cover/a/1000x1000-000000-80-0-0.jpg".artworkAt(2000))
        assertEquals("https://coverartarchive.org/release-group/g/front-250", "https://coverartarchive.org/release-group/g/front-1200".artworkAt(160))
        // A cover the archive has only at 500px isn't asked for at 1200.
        assertEquals("https://coverartarchive.org/release-group/g/front-500", "https://coverartarchive.org/release-group/g/front-500".artworkAt(1200))
    }

    @Test fun switchingTracksWithoutAResolvedCoverNeverShowsTheLastSongsCover() {
        val art = PlaybackArtwork { if (it.videoId == "one") "file:/saved-cover.jpg" else null }
        assertEquals("file:/saved-cover.jpg", art.choose(Song("one", "One", "Artist", null)))
        assertEquals("https://images.test/two", art.choose(Song("two", "Two", "Artist", "https://images.test/two")))
    }
}
