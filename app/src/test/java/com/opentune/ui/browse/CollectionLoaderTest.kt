package com.opentune.ui.browse

import com.opentune.data.Collection
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CollectionLoaderTest {
    private val first = Collection("VLmix", "Chill mix", "", null, listOf(Song("one", "First", "Artist", null)))

    @Test fun firstTracksAreUsableWhileLaterPagesArePending() {
        val later = CompletableDeferred<Collection>()
        val loader = CollectionLoader("VLmix", "test", fetch = { progress -> progress(first); later.await() }, cached = { null })
        ShadowLooper.idleMainLooper()
        assertEquals(first, loader.preview.value)
        assertTrue(loader.state.value is UiState.Loading)
        val complete = first.copy(songs = first.songs + Song("two", "Second", "Artist", null))
        later.complete(complete)
        ShadowLooper.idleMainLooper()
        assertEquals(complete, loader.preview.value)
        assertTrue(loader.state.value is UiState.Success)
    }

    @Test fun continuationFailureKeepsTracksAndCanBeRetried() {
        var attempts = 0
        val later = CompletableDeferred<Collection>()
        val loader = CollectionLoader("VLmix", "test", fetch = { progress ->
            progress(first)
            if (attempts++ == 0) later.await() else first
        }, cached = { null })
        ShadowLooper.idleMainLooper()
        later.completeExceptionally(java.io.IOException("Page timed out"))
        ShadowLooper.idleMainLooper()
        assertEquals(first, loader.preview.value)
        assertTrue(loader.state.value is UiState.Error)
        loader.reload()
        ShadowLooper.idleMainLooper()
        assertTrue(loader.state.value is UiState.Success)
        assertEquals(2, attempts)
    }
    @Test fun reopeningFreshPlaylistUsesCacheUntilExplicitRefresh() {
        var requests = 0
        val refreshed = first.copy(title = "Updated mix")
        val loader = CollectionLoader("VLmix", "test", fetch = { requests++; refreshed }, cached = { first }, fresh = { true })
        ShadowLooper.idleMainLooper()
        assertEquals(0, requests)
        assertEquals(first, (loader.state.value as UiState.Success).data)
        loader.reload()
        ShadowLooper.idleMainLooper()
        assertEquals(1, requests)
        assertEquals(refreshed, loader.preview.value)
    }

    @Test fun stalePlaylistDoesNotShrinkToItsFirstPageDuringRefresh() {
        val cached = first.copy(songs = first.songs + Song("two", "Second", "Artist", null))
        val finished = CompletableDeferred<Collection>()
        val loader = CollectionLoader("VLmix", "test", fetch = { page -> page(first); finished.await() }, cached = { cached }, fresh = { false })
        ShadowLooper.idleMainLooper()
        assertEquals(cached, loader.preview.value)
        finished.complete(first.copy(title = "Refreshed playlist"))
        ShadowLooper.idleMainLooper()
        assertEquals("Refreshed playlist", loader.preview.value?.title)
    }

}
