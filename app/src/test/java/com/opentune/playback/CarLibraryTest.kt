package com.opentune.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarLibraryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val car = CarLibrary(context)
    private val songs = listOf(
        Song("aaaaaaaaaa1", "First", "One", "https://lh3.googleusercontent.com/a=w60-h60"),
        Song("bbbbbbbbbb2", "Second", "Two", null),
        Song("cccccccccc3", "Third", "Three", null),
    )

    @Before
    fun likeSongs() {
        LibraryStore.init(context)
        // setLiked puts each newest first, so like in reverse to keep the order.
        songs.reversed().forEach { LibraryStore.setLiked(it, true) }
    }

    @Test
    fun rootHasHomeRecentAndLibrary() = runBlocking {
        val tabs = car.children(CarLibrary.ROOT, null)
        assertEquals(listOf(CarLibrary.HOME, CarLibrary.RECENT, CarLibrary.LIBRARY), tabs.map { it.mediaId })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun libraryOpensLiked() = runBlocking {
        val library = car.children(CarLibrary.LIBRARY, null)
        assertEquals(CarLibrary.LIKED, library.first().mediaId)
        val liked = car.children(CarLibrary.LIKED, null)
        assertEquals(songs.map { "${CarLibrary.LIKED}|${it.videoId}" }, liked.map { it.mediaId })
        assertTrue(liked.all { it.mediaMetadata.isPlayable == true })
        // Artwork goes through the app's content provider, which Android Auto can read.
        assertEquals("content", liked.first().mediaMetadata.artworkUri?.scheme)
    }

    @Test
    fun tappingASongQueuesItsListFromThere() = runBlocking {
        val tapped = car.children(CarLibrary.LIKED, null)[1]
        val queue = car.resolve(listOf(tapped), 0, 0L)
        assertEquals(songs.map { it.videoId }, queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
        // Plain queue items, as the app's own player builds them.
        assertEquals("opentune", queue.mediaItems[1].localConfiguration?.uri?.scheme)
    }

    @Test
    fun appItemsPassThroughWithTheirUri() = runBlocking {
        val fromApp = MediaItem.Builder().setMediaId(songs[0].videoId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("First").build()).build()
        val queue = car.resolve(listOf(fromApp, fromApp), 1, 5_000L)
        assertEquals(listOf(songs[0].videoId, songs[0].videoId), queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
        assertEquals(5_000L, queue.startPositionMs)
        assertEquals("opentune", queue.mediaItems[0].localConfiguration?.uri?.scheme)
    }

    @Test
    fun blankVoiceRequestPlaysLikedWithoutHistory() = runBlocking {
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        val queue = car.resolve(listOf(spoken), 0, 0L)
        assertEquals(songs.map { it.videoId }.toSet(), queue.mediaItems.map { it.mediaId }.toSet())
    }
}
