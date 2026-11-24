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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.performScrollTo
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.opentune.data.ExploreFeed
import com.opentune.data.history.History
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
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
    private fun show(art: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        var modes: androidx.compose.ui.input.InputModeManager? = null
        compose.setContent {
            modes = androidx.compose.ui.platform.LocalInputModeManager.current
            TvTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(TvColors.Background),
                ) {
                    // Made-up covers, drawn at once, so the renders show what a page looks like with art.
                    if (art) CompositionLocalProvider(
                        LocalInspectionMode provides true,
                        LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { request -> fakeArt(request.data.toString()) },
                    ) { content() }
                    else content()
                }
            }
        }
        compose.runOnIdle { modes!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        compose.waitForIdle()
    }

    private fun song(n: Int) = Song("id$n", "Song $n", "Artist $n", null, "3:0$n")

    @Test fun theBarNamesTheTabsAndOkOpensOne() {
        show { TvRoot(player) }
        compose.waitForIdle()
        listOf("Home", "Explore", "Library").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("Search", "Settings").forEach { compose.onNodeWithContentDescription(it).assertExists() }
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
        // The page takes the focus: its first card.
        compose.onNodeWithText("Liked songs").assertIsFocused()
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

    private fun item(n: Int, video: Boolean = false) =
        ShelfItem("Album $n", "Artist $n", "https://lh3.googleusercontent.com/a$n=w544-h544", null, "MPREb_$n", video = video)

    private fun homeShelves() = listOf(
        HomeShelf("Quick picks", (1..8).map { item(it) }, subtitle = "Start here"),
        HomeShelf("Music videos for you", (11..16).map { item(it, video = true) }, videos = true),
        HomeShelf("Albums for you", (21..28).map { item(it) }),
    )

    /** The shell on Home, its shelves already loaded, as a viewer would find it. */
    private fun showHome(recents: Int = 6) {
        if (recents > 0) {
            History.init(ApplicationProvider.getApplicationContext())
            (recents downTo 1).forEach { History.record(Song("id$it", "Song $it", "Artist $it", "https://lh3.googleusercontent.com/s$it=w226-h226", "3:0$it"), 60_000) }
        }
        nav.memoryAt(0).loads["home"] = Load.Done(homeShelves())
        show(art = true) { TvShell(nav) }
    }

    @Test fun homeOpensOnItsFirstCardWithTheShelvesUnderIt() {
        showHome()
        compose.onNodeWithText("Listen again").assertExists()
        compose.onNodeWithText("Quick picks").assertExists()
        // The page takes the focus as it opens: its first card.
        compose.onNodeWithText("Song 1").assertIsFocused()
        save("tv-home")
    }

    @Test fun theStripShowsWhatIsPlaying() {
        setPlaying(Song("np", "Bohemian Rhapsody", "Queen", "https://lh3.googleusercontent.com/np=w226-h226", "5:55"))
        showHome()
        compose.onNodeWithText("Bohemian Rhapsody").assertExists()
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithText("Song 2").requestFocus()
        save("tv-home-playing")
        compose.onNodeWithText("Bohemian Rhapsody").performClick()
        compose.waitForIdle()
        assertEquals(Dest.NowPlaying, nav.current)
    }

    @Test fun upFromThePageLandsOnTheCurrentTab() {
        showHome()
        compose.onNodeWithText("Song 1").assertIsFocused()
        compose.onNodeWithText("Song 1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithText("Home").assertIsFocused()
        // And Down goes back to the card it came from.
        compose.onNodeWithText("Home").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Song 1").assertIsFocused()
    }

    @Test fun backPutsTheFocusOnTheCardThatWasOpened() {
        showHome(recents = 0)
        compose.onNodeWithText("Album 3").requestFocus()
        compose.onNodeWithText("Album 3").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(nav.current is Dest.Collection)
        compose.runOnIdle { nav.back() }
        compose.waitForIdle()
        assertEquals(Dest.Home, nav.current)
        compose.onNodeWithText("Album 3").assertIsFocused()
    }

    @Test fun exploreShowsArtistsReleasesChartsAndMoodTiles() {
        val canvas = ExploreFeed.Canvas(
            artists = (1..8).map { ExploreFeed.Artist("UC$it", "Artist $it", "https://lh3.googleusercontent.com/f$it=s226") },
            tiles = (1..6).map { ExploreFeed.Tile.Item(item(40 + it), "new", ExploreFeed.Kind.NEW) } +
                listOf("Chill", "Workout", "Focus", "Party", "Sleep", "Romance").map { ExploreFeed.Tile.Mood(MoodGenre(it, "FEm_$it", "p$it"), ExploreFeed.Kind.MOOD) } +
                listOf("Hip-hop", "Rock", "Jazz", "Classical", "Indie").map { ExploreFeed.Tile.Mood(MoodGenre(it, "FEg_$it", "p$it"), ExploreFeed.Kind.GENRE) },
        )
        nav.goTab(Dest.Explore)
        nav.memoryAt(0).loads["explore"] = Load.Done(canvas)
        show(art = true) { TvShell(nav) }
        compose.onNodeWithText("Artists").assertExists()
        compose.onNodeWithText("New releases").assertExists()
        compose.onNodeWithText("Artist 1").assertIsFocused()
        save("tv-explore")
        // Down twice: the new releases, then the moods, whose first tile is under the first card.
        compose.onNodeWithText("Artist 1").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Album 41").assertIsFocused()
        compose.onNodeWithText("Album 41").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Chill").assertIsFocused()
        save("tv-explore-moods")
        compose.onNodeWithText("Chill").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals("Chill", (nav.current as Dest.Mood).title)
    }

    @Test fun aFailedPageOffersTryAgainWithTheFocusOnIt() {
        var tries = 0
        show { HomeContent(emptyList(), Load.Failed, emptyList(), { tries++ }, {}, {}) }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, tries)
    }

    @Test fun theStatesDrawn() {
        show {
            Column {
                TvLoadingState()
                TvErrorState({})
                TvEmptyState("Nothing here yet", message = "Liked songs show here.")
            }
        }
        compose.onNodeWithText("Try again").requestFocus()
        save("tv-states")
    }

    @Test fun theKitsPiecesDrawn() {
        show(art = true) {
            CompositionLocalProvider(LocalPageMemory provides PageMemory(99)) {
                TvPage(title = "The kit", subtitle = "Cards, buttons, rows and states", scroll = TvScroll.List) {
                    item {
                        Row(Modifier.safeSides(), horizontalArrangement = Arrangement.spacedBy(TvSpace.L), verticalAlignment = Alignment.CenterVertically) {
                            TvPillButton("Play", {}, icon = Icons.Rounded.PlayArrow, primary = true)
                            TvPillButton("Shuffle", {}, icon = Icons.Rounded.Shuffle)
                            TvIconButton(Icons.Rounded.Favorite, "Like", {}, active = true, activeTint = TvColors.Like)
                            TvTextTile("Chill", {})
                        }
                    }
                    item {
                        Column(Modifier.safeSides()) {
                            TvSongRow(song(1), {}, index = 1, playing = true)
                            TvSongRow(song(2), {}, index = 2)
                            TvSongRow(song(3), {}, playing = true)
                            TvRowButton("Streaming quality", {}, subtitle = "Best available", icon = Icons.Rounded.Settings, value = "High")
                        }
                    }
                    item { TvLoadingState(kind = TvLoadingKind.Rows) }
                    item { TvEmptyState("Nothing here yet") }
                }
            }
        }
        compose.onNodeWithText("Shuffle").requestFocus()
        save("tv-kit")
    }

    @Test fun artworkIsAskedForAtTheSizeItIsDrawn() {
        assertEquals("https://lh3.googleusercontent.com/x=w320-h320-l90-rj", artUrl("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", 320))
        assertEquals("https://yt3.ggpht.com/y=w128-h128-p", artUrl("https://yt3.ggpht.com/y=w900-h900-p", 128))
        assertEquals("https://i.ytimg.com/vi/a/hqdefault.jpg", artUrl("https://i.ytimg.com/vi/a/hqdefault.jpg", 320))
        assertEquals(null, artUrl(null, 320))
    }

    /** Puts [song] in the player as if it were playing, for the bar's strip. */
    private fun setPlaying(song: Song) {
        val field = com.opentune.playback.PlayerConnection::class.java.getDeclaredField("_currentSong").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(player.player) as kotlinx.coroutines.flow.MutableStateFlow<Song?>).value = song
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

    /** A plain two-tone square standing in for a cover, its colours from the URL. */
    private fun fakeArt(url: String): coil3.Image {
        val hue = Math.floorMod(url.hashCode(), 360).toFloat()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.55f)))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.5f, 0.8f)) }
        canvas.drawCircle(40f, 26f, 18f, paint)
        return bitmap.asImage()
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
