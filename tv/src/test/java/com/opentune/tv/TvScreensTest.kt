package com.opentune.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.playback.AudioFormatInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The TV app on a 1080p television: what each page shows, and that the remote's arrows and OK drive it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var player: TvPlayer
    private lateinit var nav: TvNav

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(app)
        LibraryStore.init(app)
        player = TvPlayer(app, connectNow = false)
        nav = TvNav(mutableStateListOf<Dest>(Dest.Home), player)
    }

    /** Draws [content] with the remote driving, as on a TV: arrows move focus, and focus rings show. */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        var modes: androidx.compose.ui.input.InputModeManager? = null
        compose.setContent {
            modes = androidx.compose.ui.platform.LocalInputModeManager.current
            TvTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(TvColors.Background),
                ) { content() }
            }
        }
        compose.runOnIdle { modes!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        compose.waitForIdle()
    }

    private fun song(n: Int) = Song("id$n", "Song $n", "Artist $n", null, "3:0$n")

    @Test fun theBarNamesTheTabsAndOkOpensOne() {
        show { TvRoot(player) }
        compose.waitForIdle()
        listOf("Home", "Explore", "Library", "Now Playing").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your music").assertExists()
        save("tv-library")
    }

    @Test fun rightArrowMovesAlongTheBarAndOkOpens() {
        show { TvRoot(player) }
        compose.onNodeWithText("Home").requestFocus()
        compose.onNodeWithText("Home").assertIsFocused()
        compose.onNodeWithText("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("Explore").assertIsFocused()
        compose.onNodeWithText("Explore").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("Library").assertIsFocused()
        // OK opens it; the library needs no network, so its first row is there at once.
        compose.onNodeWithText("Library").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Your music").assertExists()
    }

    @Test fun aSpokenSearchLandsInTheSearchField() {
        nav.pendingSearch = "Pink Floyd"
        show { SearchScreen(nav) }
        compose.onNodeWithText("Pink Floyd").assertExists()
        assertEquals(null, nav.pendingSearch)
    }

    @Test fun backReturnsFromAPageToTheOneBeneath() {
        nav.open(Dest.Liked)
        assertEquals(Dest.Liked, nav.current)
        assertTrue(nav.back())
        assertEquals(Dest.Home, nav.current)
        assertTrue(!nav.back())
    }

    @Test fun likedSongsListWithPlayAndShuffle() {
        LibraryStore.setLiked(song(1), true)
        LibraryStore.setLiked(song(2), true)
        show { LikedScreen(nav) }
        compose.waitForIdle()
        compose.onNodeWithText("Liked songs").assertExists()
        compose.onNodeWithText("Play").assertExists()
        compose.onNodeWithText("Shuffle").assertExists()
        compose.onNodeWithText("Song 1").assertExists()
        save("tv-liked")
    }

    @Test fun nothingPlayingSaysSoAndOffersHome() {
        nav.open(Dest.NowPlaying)
        show { NowPlayingScreen(nav) }
        compose.waitForIdle()
        compose.onNodeWithText("Nothing is playing").assertExists()
        compose.onNodeWithText("Go to Home").performClick()
        assertEquals(Dest.Home, nav.current)
    }

    @Test fun syncedLyricsShowTheLinesAndTheSeekBarTheTimes() {
        val lines = listOf("Hello", "darkness", "my old friend").mapIndexed { i, t ->
            LyricLine(i * 4_000L, i * 4_000L + 3_000, t, emptyList(), false)
        }
        show {
            androidx.compose.foundation.layout.Column {
                SyncedLyrics(Lyrics.Synced(lines, "test"), 4_500)
                SeekBar(position = 65_000, duration = 200_000, onSeekBy = {})
            }
        }
        compose.waitForIdle()
        lines.forEach { compose.onNodeWithText(it.text).assertExists() }
        compose.onNodeWithText("1:05").assertExists()
        compose.onNodeWithText("-2:15").assertExists()
        save("tv-lyrics")
    }

    @Test fun settingsOffersSignInWhenSignedOut() {
        show { SettingsScreen(nav) }
        compose.waitForIdle()
        compose.onNodeWithText("Use your phone to bring your YouTube Music account here").assertExists()
        compose.onNodeWithText("Streaming quality").assertExists()
        save("tv-settings")
    }

    @Test fun clockReadsHoursOnlyWhenNeeded() {
        assertEquals("0:00", clock(0))
        assertEquals("3:07", clock(187_400))
        assertEquals("1:02:03", clock(3_723_000))
        assertEquals("0:00", clock(-5))
    }

    @Test fun theFormatLineNamesDolbyAndTheRest() {
        val atmos = AudioFormatInfo("ec+3", 768, 48_000, 6, mime = "audio/eac3-joc")
        assertEquals("Dolby Atmos · 48 kHz · 768 kbps", TvPlayer.formatLine(atmos, null))
        assertEquals("", TvPlayer.formatLine(null, null))
    }

    @Test fun searchSortsRowsIntoSongsArtistsAlbumsAndPlaylists() {
        fun browse(id: String, type: BrowseType) = SearchResult.Browse(BrowseItem(id, id, "", null, type))
        val found = Found.of(listOf(
            SearchResult.TopTrack(song(1)), SearchResult.Track(song(2)), SearchResult.Track(song(1)),
            browse("a", BrowseType.ARTIST), browse("b", BrowseType.ALBUM), browse("c", BrowseType.PLAYLIST),
        ))
        assertEquals(listOf("id1", "id2"), found.songs.map { it.videoId })
        assertEquals(listOf("a"), found.artists.map { it.browseId })
        assertEquals(listOf("b"), found.albums.map { it.browseId })
        assertEquals(listOf("c"), found.playlists.map { it.browseId })
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
