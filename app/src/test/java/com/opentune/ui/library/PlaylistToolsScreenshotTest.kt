package com.opentune.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import com.opentune.data.download.Downloads
import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.library.SongRef
import org.junit.Assert.assertEquals
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Before
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.semantics.getOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A phone playlist with Add songs and Download all, and the Add songs sheet, to build/screenshots/playlist. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PlaylistToolsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var id: String
    private val songs = listOf(
        Song("aaaaaaaaaaa", "Husn", "Anuv Jain", null, "3:38"),
        Song("bbbbbbbbbbb", "Kesariya", "Arijit Singh", null, "4:28"),
        Song("ccccccccccc", "Blinding Lights", "The Weeknd", null, "3:20"),
    )

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        // Glass now matches the account playlist page. Robolectric's software
        // screenshot canvas cannot draw RuntimeShader; exercise its reduced-blur layout.
        AppSettings.updateUi { it.copy(reduceBlur = true) }
        LibraryStore.init(context)
        id = LibraryStore.createPlaylist("Late nights")
        LibraryStore.addAllToPlaylist(id, songs.take(2))
        songs.forEach { History.record(it, 200_000) }
    }

    /** Long press, Select, then a second song: both ticked, the bar over the top saying so; Remove takes both out. */
    @Test fun pickSeveral() {
        val songsIn = { LibraryStore.playlist(id)!!.songs.size }
        LibraryStore.addToPlaylist(id, songs[2])
        compose.setContent { OpenTuneTheme(ThemeSettings()) { Surface(color = MaterialTheme.colorScheme.background) {
            LocalPlaylistScreen(id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {})
        } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Husn").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Select").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Blinding Lights").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("2 selected").assertExists()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/playlist/select.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Remove").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        org.junit.Assert.assertEquals(1, songsIn())
        org.junit.Assert.assertEquals("Kesariya", LibraryStore.playlist(id)!!.songs.single().title)
    }

    @Test fun page() = shoot("playlist/page") { LocalPlaylistScreen(id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {}) }

    /** With Settings › Full-screen playlist covers on, the cover runs across the top of the page. */
    @Test fun fullCover() {
        val before = com.opentune.data.settings.AppSettings.ui.value
        com.opentune.data.settings.AppSettings.updateUi { it.copy(fullScreenListCover = true) }
        try {
            shoot("playlist/full-cover") { LocalPlaylistScreen(id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {}) }
        } finally {
            com.opentune.data.settings.AppSettings.updateUi { before }
        }
    }

    @Test fun spotifyUsesTheSamePlaylistLayout() {
        LibraryStore.putSpotifyPlaylist("1234567890123456789012", "Late nights", songs, "revision", null)
        val spotify = LibraryStore.playlists.value.first { it.spotifyId != null }
        shoot("playlist/spotify") { LocalPlaylistScreen(spotify.id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {}) }
        compose.onNodeWithText("Spotify").assertExists()
        compose.onNodeWithText("Search this list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add songs").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Add songs").assertDoesNotExist()
    }

    @Test fun spotifyPlaylistsAreNotOfferedAsWriteTargets() {
        LibraryStore.putSpotifyPlaylist("1234567890123456789012", "Spotify collection", songs, "revision", null)
        compose.setContent { OpenTuneTheme(ThemeSettings()) {
            com.opentune.ui.components.AddToPlaylistDialog(songs.last(), onDone = {}, onDismiss = {})
        } }
        compose.onNodeWithText("Spotify collection").assertDoesNotExist()
        compose.onNodeWithText("Late nights").assertExists()
    }

    /** Adding several songs to a playlist that has some of them: it says so, and only the missing ones go in. */
    @Test fun addingSeveralSkipsTheOnesAlreadyThere() {
        compose.setContent { OpenTuneTheme(ThemeSettings()) {
            com.opentune.ui.components.AddAllToPlaylistDialog(songs, onDismiss = {}, onDone = {})
        } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("2 of 3 already in it · 2 songs").assertExists()
        compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Late nights")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertEquals(songs.map { it.videoId }, LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }

    /** Picking two playlists with their round buttons, then adding to both at once. */
    @Test fun addingToSeveralPlaylistsAtOnce() {
        val gym = LibraryStore.createPlaylist("Gym")
        compose.setContent { OpenTuneTheme(ThemeSettings()) {
            com.opentune.ui.components.AddAllToPlaylistDialog(songs, onDismiss = {}, onDone = {})
        } }
        compose.mainClock.advanceTimeBy(1_000)
        fun clickLabelled(label: String) = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher(label) {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label
        }).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        clickLabelled("Pick Gym")
        clickLabelled("Pick Late nights")
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Add to 2 playlists")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertEquals(songs.map { it.videoId }, LibraryStore.playlist(gym)!!.songs.map { it.videoId })
        org.junit.Assert.assertEquals(songs.map { it.videoId }, LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }

    /** A playlist that has the song already keeps its tick while others are picked, can't be picked, and its tick takes the song out. */
    @Test fun aPlaylistWithTheSongCantBePickedAgain() {
        val gym = LibraryStore.createPlaylist("Gym")
        compose.setContent { OpenTuneTheme(ThemeSettings()) {
            com.opentune.ui.components.AddToPlaylistDialog(songs[0], onDone = {}, onDismiss = {})
        } }
        compose.mainClock.advanceTimeBy(1_000)
        fun clickLabelled(label: String) = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher(label) {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label
        }).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        clickLabelled("Pick Gym")
        compose.mainClock.advanceTimeBy(500)
        // The one with the song keeps its tick, and offers to take the song out rather than be picked.
        compose.onAllNodesWithContentDescription("In this playlist", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("pick late nights") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == "Pick Late nights"
        }).assertCountEquals(0)
        compose.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText("Add to 1 playlist")), useUnmergedTree = true).assertExists()
        org.junit.Assert.assertTrue(LibraryStore.playlist(gym)!!.songs.isEmpty())
        // Its tick taken off: the song comes out of it.
        clickLabelled("Remove from Late nights")
        compose.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertEquals(listOf(songs[1].videoId), LibraryStore.playlist(id)!!.songs.map { it.videoId })
    }

    /**
     * Searching a list: the field sits on the bar's own frost (no band of another colour behind
     * it), and has no X of its own until something is typed; the bar's X closes the search.
     */
    @Test fun searchOpen() {
        compose.setContent { OpenTuneTheme(ThemeSettings()) { Surface(color = MaterialTheme.colorScheme.background) {
            LocalPlaylistScreen(id, PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {})
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Search this list").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithContentDescription("Clear search").assertCountEquals(0)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/playlist/search.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun addSheet() = shoot("playlist/add") { AddSongsSheet(id, onDismiss = {}) }

    @Test fun completedPlaylistCanOpenRemovalAndKeepItsDownloads() {
        Downloads.init(ApplicationProvider.getApplicationContext())
        songs.forEach { song -> Downloads.put(song.videoId) { DownloadEntry(SongRef.of(song), DownloadState.DONE) } }
        shoot("playlist/download-management") { DownloadAllButton(songs) }
        compose.onNodeWithText("Downloaded · Manage").assertIsEnabled().performClick()
        compose.onNodeWithText("Remove all downloads").performClick()
        compose.onNodeWithText("Remove playlist downloads?").assertExists()
        compose.onNodeWithText("Keep downloads").performClick()
        assertEquals(3, songs.count { Downloads.entryNow(it.videoId)?.state == DownloadState.DONE })
    }

    @Test fun inProgressPlaylistOffersCancelAndRemoveSeparately() {
        Downloads.init(ApplicationProvider.getApplicationContext())
        Downloads.put(songs[0].videoId) { DownloadEntry(SongRef.of(songs[0]), DownloadState.DONE) }
        Downloads.put(songs[1].videoId) { DownloadEntry(SongRef.of(songs[1]), DownloadState.DOWNLOADING) }
        shoot("playlist/download-progress") { DownloadAllButton(songs) }
        compose.onNodeWithText("Downloading · 1/3").performClick()
        compose.onNodeWithText("Cancel downloads").assertExists()
        compose.onNodeWithText("Remove all downloads").assertExists()
        compose.onNodeWithText("Download remaining songs").assertExists()
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { OpenTuneTheme(ThemeSettings()) { Surface(color = MaterialTheme.colorScheme.background) { content() } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // The sheet is a dialog window of its own; draw the topmost window.
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
