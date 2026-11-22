package com.opentune.playback

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.opentune.data.model.NOTIFICATION_ART_PX
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cover the notification, lock screen and car get: sized for them, not a list's thumbnail. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationArtworkTest {
    @Test
    fun aListsSmallThumbnailIsAskedForAtTheMediaCardsSize() {
        val song = Song("aaaaaaaaaa1", "Dil Deewana", "Kumar Sanu", "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj")
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w$NOTIFICATION_ART_PX-h$NOTIFICATION_ART_PX-l90-rj",
            song.toMediaItem().mediaMetadata.artworkUri.toString(),
        )
    }

    @Test
    fun aCoverWithNoSizeInItsAddressIsLeftAsItIs() {
        val song = Song("aaaaaaaaaa1", "Song", "Artist", "https://i.ytimg.com/vi/aaaaaaaaaa1/hqdefault.jpg")
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaa1/hqdefault.jpg", song.toMediaItem().mediaMetadata.artworkUri.toString())
    }
}
