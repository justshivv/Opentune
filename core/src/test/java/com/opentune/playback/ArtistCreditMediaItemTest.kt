package com.opentune.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.opentune.data.model.Song
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArtistCreditMediaItemTest {
    private val credit = Song("abcdefghijk", "Song", "Actual Artist", null, artistId = "actual-artist")

    @Test fun repairingCreditsPreservesSelectedFlacAndQueueExtras() {
        val item = MediaItem.Builder().setMediaId(credit.videoId)
            .setUri(externalStreamUri(credit.videoId, "verified-flac")).setMimeType("audio/flac")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").setArtist("Various Artist")
                .setExtras(Bundle().apply { putString("artistId", "compilation-artist"); putString("queue-marker", "keep") }).build())
            .build()
        val corrected = item.withTrackArtistCredit(credit)
        assertEquals(item.localConfiguration, corrected.localConfiguration)
        assertEquals("Actual Artist", corrected.mediaMetadata.artist)
        assertEquals("actual-artist", corrected.toSong().artistId)
        assertEquals("keep", corrected.mediaMetadata.extras?.getString("queue-marker"))
        assertSame(item, item.withTrackArtistCredit(credit.copy(videoId = "another-song")))
        assertSame(item, item.withTrackArtistCredit(credit.copy(artist = "Various Artists")))
        assertSame(corrected, corrected.withTrackArtistCredit(credit.copy(artist = "Someone Else")))
    }
}
