package com.opentune.playback

import androidx.media3.datasource.DataSpec
import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StartupSelectionTest {
    @Test fun playerRetainsTheSourceFactorySelectionToken() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val delegate = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
        val factory = object : androidx.media3.exoplayer.source.MediaSource.Factory by delegate {
            override fun createMediaSource(item: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource =
                delegate.createMediaSource(item.buildUpon().setUri(original.buildUpon().appendQueryParameter("startup", "test").build()).build())
        }
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
        try {
            player.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(song.videoId).setUri(original).build())
            assertEquals("test", startupToken(player.currentMediaItem!!.localConfiguration!!.uri))
        } finally {
            player.release()
        }
    }

    private val song = Song("abcdefghijk", "Song", "Artist", null, durationText = "3:00")
    private val original = streamUri(song.videoId, false)
    private val spec = DataSpec.Builder().setUri(original).setPosition(42).build()
    private val flac = ExternalStreams.Rendition("flac", "https://cdn.example/audio", "24-bit FLAC", true, 0)

    @Test fun maximumWaitsForExternalSearchBeforeSelectingAnyStream() = runBlocking {
        val gate = CompletableDeferred<ExternalStreams.Rendition?>()
        val selection = StartupSelection(song, original, { true }) { _, duration ->
            assertEquals(180_000L, duration)
            gate.await()
        }
        val pending = async { selection.resolve(spec) }
        yield()
        assertFalse(pending.isCompleted)
        assertNull(selection.selected)
        gate.complete(flac)
        val chosen = pending.await()
        assertEquals("flac", externalStreamKeyOf(chosen.uri))
        assertNotEquals(cacheKeyOf(original), cacheKeyOf(chosen.uri))
        assertEquals(42L, chosen.position)
    }

    @Test fun seeksKeepTheSelectedRenditionEvenIfSettingsChange() = runBlocking {
        var maximum = true
        var calls = 0
        val selection = StartupSelection(song, original, { maximum }) { _, _ -> calls++; flac }
        val first = selection.resolve(spec)
        maximum = false
        val seek = selection.resolve(spec.buildUpon().setPosition(900).build())
        assertEquals(first.uri, seek.uri)
        assertEquals(900L, seek.position)
        assertEquals(1, calls)
    }

    @Test fun missingOrFailedProvidersFallBackToYouTube() = runBlocking {
        val unavailable = StartupSelection(song, original, { true }) { _, _ -> null }
        assertEquals(original, unavailable.resolve(spec).uri)
        val failed = StartupSelection(song, original, { true }) { _, _ -> throw IOException("Provider down") }
        assertEquals(original, failed.resolve(spec).uri)
    }

    @Test fun lowerQualityDoesNotWaitForExternalProviders() = runBlocking {
        val selection = StartupSelection(song, original, { false }) { _, _ -> error("Unexpected lookup") }
        assertEquals(original, selection.resolve(spec).uri)
        assertFalse(selection.checkedMaximum)
    }

    @Test fun cancellationDoesNotPinAnUnwantedFallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val selection = StartupSelection(song, original, { true }) { _, _ ->
            started.complete(Unit)
            CompletableDeferred<ExternalStreams.Rendition?>().await()
        }
        val pending = async { selection.resolve(spec) }
        started.await()
        pending.cancelAndJoin()
        assertNull(selection.selected)
    }

    @Test fun fallbackBypassesSelectionAndRetaggingDoesNotKeepOldTokens() {
        val fallback = youtubeFallbackUri(song.videoId, upgraded = true)
        assertEquals("youtube", fallback.getQueryParameter("fallback"))
        assertTrue(isUpgradedUri(fallback))
        val tagged = fallback.buildUpon().appendQueryParameter("startup", "old").build()
        assertEquals(fallback, withoutStartup(tagged))
    }
}
