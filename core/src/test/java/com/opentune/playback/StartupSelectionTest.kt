package com.opentune.playback

import androidx.media3.datasource.DataSpec
import com.opentune.data.lossless.ExternalStreams
import com.opentune.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test fun maximumStartsAfterAQuickJioSaavnMiss() = runBlocking {
        var calls = 0
        val selection = StartupSelection(song, original, { true }, findJioSaavn = { _, _ -> null }) { id ->
            assertEquals(song.videoId, id)
            calls++
            null
        }
        val chosen = selection.resolve(spec)
        assertEquals(original, chosen.uri)
        assertEquals(42L, chosen.position)
        assertEquals(1, calls)
    }

    @Test fun cachedMaximumUsesItsOwnCacheKeyWithoutSearchingAgain() = runBlocking {
        val selection = StartupSelection(song, original, { true }, findJioSaavn = { _, _ -> null }) { flac }
        val chosen = selection.resolve(spec)
        assertEquals("flac", externalStreamKeyOf(chosen.uri))
        assertNotEquals(cacheKeyOf(original), cacheKeyOf(chosen.uri))
        assertEquals(42L, chosen.position)
    }

    @Test fun seeksKeepTheSelectedRenditionEvenIfSettingsChange() = runBlocking {
        var maximum = true
        var calls = 0
        val selection = StartupSelection(song, original, { maximum }, findJioSaavn = { _, _ -> error("Cached source must not search") }) { calls++; flac }
        val first = selection.resolve(spec)
        maximum = false
        val seek = selection.resolve(spec.buildUpon().setPosition(900).build())
        assertEquals(first.uri, seek.uri)
        assertEquals(900L, seek.position)
        assertEquals(1, calls)
    }

    @Test fun lowerQualityDoesNotCheckExternalCache() = runBlocking {
        val selection = StartupSelection(song, original, { false }, { false }, picked = { null }, saavnWanted = { false }, findJioSaavn = { _, _ -> error("Unexpected lookup") }) { error("Unexpected lookup") }
        assertEquals(original, selection.resolve(spec).uri)
    }

    @Test fun jioSaavnIsTriedFirstWheneverItsAllowed() = runBlocking {
        val saavn = ExternalStreams.Rendition("saavn", "https://cdn.example/aac", "JioSaavn · 320 kbps (lossy)", false, 0)
        val selection = StartupSelection(song, original, { false }, { false }, picked = { null }, saavnWanted = { true }, findJioSaavn = { _, _ -> saavn }) { null }
        assertEquals("saavn", externalStreamKeyOf(selection.resolve(spec).uri))
    }

    @Test fun aLosslessFileFoundAheadStartsTheSongBelowMax() = runBlocking {
        val selection = StartupSelection(song, original, { false }, { true }, picked = { null }, saavnWanted = { false }, findJioSaavn = { _, _ -> error("Below Max nothing is searched") }) { flac }
        assertEquals("flac", externalStreamKeyOf(selection.resolve(spec).uri))
    }

    @Test fun aLateExternalResultCannotChangeBytesDuringASeek() = runBlocking {
        var available: ExternalStreams.Rendition? = null
        val selection = StartupSelection(song, original, { true }, findJioSaavn = { _, _ -> null }) { available }
        assertEquals(original, selection.resolve(spec).uri)
        available = flac
        assertEquals(original, selection.resolve(spec.buildUpon().setPosition(900).build()).uri)
        // A fresh media source may use it, with a different cache key.
        val next = StartupSelection(song, original, { true }, findJioSaavn = { _, _ -> null }) { available }
        assertEquals("flac", externalStreamKeyOf(next.resolve(spec).uri))
    }

    @Test fun concurrentOpensKeepOneRendition() = runBlocking {
        var calls = 0
        val selection = StartupSelection(song, original, { true }, findJioSaavn = { _, _ -> null }) { calls++; flac.copy(key = "key-$calls") }
        val first = async { selection.resolve(spec) }
        val second = async { selection.resolve(spec.buildUpon().setPosition(900).build()) }
        assertEquals(first.await().uri, second.await().uri)
        assertEquals(1, calls)
    }

    @Test fun aSlowProviderCannotHoldStartupButItsAnswerIsNotThrownAway() = runBlocking {
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        val selection = StartupSelection(song, original, { true }, lookupBudgetMs = 20,
            findJioSaavn = { _, _ ->
                try { kotlinx.coroutines.delay(300); answered.complete(Unit); null } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
            }, cached = { null })
        val result = kotlinx.coroutines.withTimeout(2_000) { selection.resolve(spec) }
        // The song starts on YouTube at once...
        assertEquals(original, result.uri)
        // ...while the lookup runs on to its answer, for the upgrade to use.
        kotlinx.coroutines.withTimeout(2_000) { answered.await() }
        assertFalse(cancelled)
    }

    @Test fun aQuickJioSaavnMatchTakesPriorityOverYouTube() = runBlocking {
        val saavn = flac.copy(key = "saavn", label = "JioSaavn", lossless = false)
        val selection = StartupSelection(song, original, { true },
            findJioSaavn = { _, duration -> assertEquals(180_000L, duration); saavn }, cached = { null })
        assertEquals("saavn", externalStreamKeyOf(selection.resolve(spec).uri))
    }

    @Test fun cancelledStartupNeverPinsAFallback() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val selection = StartupSelection(song, original, { true },
            findJioSaavn = { _, _ -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() }, cached = { null })
        val pending = async { selection.resolve(spec) }
        started.await()
        pending.cancel()
        pending.join()
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


