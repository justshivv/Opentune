package com.opentune.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.opentune.data.model.QueueTier
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The gapless-album guard: one album played in its order is never blended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GaplessAlbumsTest {
    private fun spot(id: String? = "MPREb_live", track: Int? = null, place: Int? = null, name: String? = null, artist: String? = null, tier: QueueTier = QueueTier.CONTEXT) =
        AlbumSpot(albumId = id, albumName = name, artist = artist, trackNumber = track, listPosition = place, tier = tier)

    @Test fun oneAlbumInItsOrderRunsOn() {
        assertTrue(albumRunsOn(spot(place = 3), spot(place = 4), adjacent = true))
        assertTrue(albumRunsOn(spot(track = 7), spot(track = 8), adjacent = true))
    }

    @Test fun outOfOrderOrApartItBlends() {
        // Shuffled: the list's places aren't one after the other.
        assertFalse(albumRunsOn(spot(place = 3), spot(place = 9), adjacent = true))
        assertFalse(albumRunsOn(spot(place = 4), spot(place = 3), adjacent = true))
        assertFalse(albumRunsOn(spot(track = 2), spot(track = 5), adjacent = true))
        // Not the entry right after, or the player shuffling.
        assertFalse(albumRunsOn(spot(place = 3), spot(place = 4), adjacent = false))
    }

    @Test fun trackNumbersWinOverTheList() {
        // A playlist that has the album's songs out of order, numbered.
        assertFalse(albumRunsOn(spot(track = 5, place = 0), spot(track = 2, place = 1), adjacent = true))
        // A zero track number means none.
        assertTrue(albumRunsOn(spot(track = 0, place = 0), spot(track = 0, place = 1), adjacent = true))
    }

    @Test fun differentAlbumsAlwaysBlend() {
        assertFalse(albumRunsOn(spot(id = "MPREb_a", place = 0), spot(id = "MPREb_b", place = 1), adjacent = true))
        assertFalse(albumRunsOn(spot(id = null, place = 0), spot(id = null, place = 1), adjacent = true))
    }

    @Test fun withoutIdsTheNameAndArtistMustMatch() {
        val a = spot(id = null, name = "Live at Wembley", artist = "Queen", place = 0)
        assertTrue(albumRunsOn(a, a.copy(listPosition = 1, albumName = "live at wembley "), adjacent = true))
        assertFalse(albumRunsOn(a, a.copy(listPosition = 1, artist = "Someone else"), adjacent = true))
        assertFalse(albumRunsOn(a, a.copy(listPosition = 1, artist = null), adjacent = true))
        // One with an id and one without: told by name.
        assertTrue(albumRunsOn(a.copy(albumId = "MPREb_q"), a.copy(listPosition = 1), adjacent = true))
    }

    @Test fun withNoOrderToGoByOnlyTheListsOwnNeighboursRunOn() {
        assertTrue(albumRunsOn(spot(), spot(), adjacent = true))
        // A song queued by hand, or by autoplay, next to one of the album's.
        assertFalse(albumRunsOn(spot(), spot(tier = QueueTier.USER), adjacent = true))
        assertFalse(albumRunsOn(spot(tier = QueueTier.AUTOPLAY), spot(), adjacent = true))
    }

    @Test fun aQueuedSongCarriesItsAlbumAndPlace() {
        val song = Song("aaaaaaaaaa1", "Bohemian Rhapsody", "Queen", null, albumId = "MPREb_live", albumName = "Live Aid")
        val spot = song.toMediaItem().atListPosition(4).albumSpot
        assertEquals("MPREb_live", spot.albumId)
        assertEquals("Live Aid", spot.albumName)
        assertEquals("Queen", spot.artist)
        assertEquals(4, spot.listPosition)
        assertEquals(QueueTier.CONTEXT, spot.tier)
        // The album id comes back with the song, and nothing else changes.
        assertEquals("MPREb_live", song.toMediaItem().toSong().albumId)
        assertNull(song.toMediaItem().albumSpot.listPosition)
    }

    @Test fun aTrackNumberFromTheFileCounts() {
        val item = MediaItem.Builder().setMediaId("x")
            .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle("Album").setArtist("Artist").setTrackNumber(3).build()).build()
        assertEquals(3, item.albumSpot.trackNumber)
        assertEquals("Album", item.albumSpot.albumName)
    }
}
