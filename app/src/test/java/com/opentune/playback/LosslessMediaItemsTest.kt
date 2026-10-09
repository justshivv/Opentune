package com.opentune.playback

import android.net.Uri
import com.opentune.data.lossless.LosslessStreams
import com.opentune.data.settings.PlaybackSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LosslessMediaItemsTest {
    @Test fun eachRenditionHasItsOwnCacheKeyAndKeepsTrackIdentity() {
        val id = "abcdefghijk"
        val uris = listOf(streamUri(id, false), streamUri(id, true), losslessUri(id, "first"), losslessUri(id, "second"))
        assertEquals(4, uris.map(::cacheKeyOf).toSet().size)
        assertTrue(uris.all { videoIdOf(it) == id })
        assertFalse(isUpgradedUri(uris[2]))
        assertEquals("first", losslessKeyOf(uris[2]))
        assertNull(losslessKeyOf(Uri.parse("file:///music/a.flac")))
    }

    @Test fun missingRenditionThrowsInsteadOfReturningYouTubeBytesUnderFlacKey() {
        assertThrows(IOException::class.java) { LosslessStreams.resolve("abcdefghijk", "expired") }
    }

    @Test fun existingSettingsRemainOptOutAndNewSettingsRoundTrip() {
        val old = Json.decodeFromString<PlaybackSettings>("{}")
        assertFalse(old.losslessStreaming)
        assertTrue(old.losslessUnmeteredOnly)
        val settings = old.copy(losslessStreaming = true, losslessHiRes = true, losslessUnmeteredOnly = false)
        assertEquals(settings, Json.decodeFromString<PlaybackSettings>(Json.encodeToString(PlaybackSettings.serializer(), settings)))
    }
}
