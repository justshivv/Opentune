package com.opentune.tv

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.opentune.data.account.AccountStore
import com.opentune.data.history.History
import com.opentune.data.library.LibraryCache
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Account
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.remote.PairedPhones
import com.opentune.data.remote.RemoteStorage
import com.opentune.data.settings.AppSettings
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2A of the TV redesign: Search, Library, Settings and the codes a phone scans (sign in,
 * pair a phone), drawn at 1080p and driven with the remote's keys.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvScreensPhase2aTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var player: TvPlayer
    private lateinit var nav: TvNav

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(app)
        LibraryStore.init(app)
        History.init(app)
        player = TvPlayer(app, connectNow = false)
        nav = TvNav(mutableStateListOf<Dest>(Dest.Home), player)
    }

    @After fun signOut() {
        setFlow(AccountStore, "_signedIn", false)
        setFlow(AccountStore, "_account", null)
        setFlow(LibraryCache, "_playlists", emptyList<ShelfItem>())
    }

    private fun show(art: Boolean = true, content: @Composable () -> Unit) {
        var modes: androidx.compose.ui.input.InputModeManager? = null
        compose.setContent {
            modes = androidx.compose.ui.platform.LocalInputModeManager.current
            TvTheme {
                Box(Modifier.fillMaxSize().background(TvColors.Background)) {
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

    private fun song(n: Int) = Song("id$n", "Song $n", "Artist $n", "https://lh3.googleusercontent.com/s$n=w226-h226", "3:0$n")
    private fun browse(n: Int, type: BrowseType, title: String) =
        BrowseItem("B$type$n", "$title $n", "Artist $n", "https://lh3.googleusercontent.com/b$type$n=w544-h544", type)

    private fun found() = Found(
        songs = (1..8).map(::song),
        artists = (1..6).map { browse(it, BrowseType.ARTIST, "Band") },
        albums = (1..6).map { browse(it, BrowseType.ALBUM, "Album") },
        playlists = (1..6).map { browse(it, BrowseType.PLAYLIST, "Playlist") },
    )

    // ---- Search ----

    @Test fun searchOpensTypingWithTheRecentSearchesUnderTheField() {
        AppSettings.clearRecentSearches()
        listOf("Daft Punk", "lofi", "Arijit Singh").forEach(AppSettings::addRecentSearch)
        nav.goTab(Dest.Search)
        show { TvShell(nav) }
        // Opened fresh: the field is typing, so the TV's keyboard is up.
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Recent searches").assertExists()
        compose.onNodeWithText("Arijit Singh").assertExists()
        save("tv-search-recent")
        // A recent search runs again.
        compose.onNodeWithText("Daft Punk").performClick()
        compose.waitForIdle()
        // It is in the field, at rest, and the focus is there while the results come.
        compose.onAllNodesWithText("Daft Punk").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun searchWithNothingTypedNorRecentOffersAHint() {
        AppSettings.clearRecentSearches()
        nav.goTab(Dest.Search)
        show { TvShell(nav) }
        compose.onNodeWithText("Find something to play").assertExists()
        save("tv-search-empty")
    }

    @Test fun typingInTheFieldShowsWhatWasTyped() {
        AppSettings.clearRecentSearches()
        show { SearchScreen(nav) }
        compose.onNode(hasSetTextAction()).performTextInput("queen")
        compose.waitForIdle()
        compose.onNodeWithText("queen").assertExists()
    }

    @Test fun searchResultsComeAsRowsAndBackLandsOnTheCardOpened() {
        nav.goTab(Dest.Search)
        nav.pendingSearch = "Daft Punk"
        nav.memoryAt(0).loads[searchKey("Daft Punk")] = Load.Done(found())
        show { TvShell(nav) }
        compose.onNodeWithText("Songs").assertExists()
        compose.onNodeWithText("Artists").assertExists()
        // A spoken search: the field holds it, at rest, with the focus on it.
        compose.onNodeWithText("Daft Punk").assertIsFocused()
        compose.onNodeWithText("Daft Punk").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Song 1").assertIsFocused()
        save("tv-search")
        compose.onNodeWithText("Song 1").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Band 1").assertIsFocused()
        save("tv-search-artists")
        compose.onNodeWithText("Band 1").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(nav.current is Dest.Artist)
        compose.runOnIdle { nav.back() }
        compose.waitForIdle()
        assertEquals(Dest.Search, nav.current)
        // The results come back at once, and the focus on the card that was opened.
        compose.onNodeWithText("Band 1").assertIsFocused()
        assertEquals("Daft Punk", AppSettings.recentSearches.value.first())
    }

    @Test fun aSearchThatFindsNothingSaysSo() {
        nav.goTab(Dest.Search)
        nav.pendingSearch = "zzqx"
        nav.memoryAt(0).loads[searchKey("zzqx")] = Load.Done(Found(emptyList(), emptyList(), emptyList(), emptyList()))
        show { TvShell(nav) }
        compose.onNodeWithText("Nothing found for \"zzqx\"").assertExists()
    }

    // ---- Library ----

    private fun fillLibrary() {
        LibraryStore.setLiked(song(1), true)
        LibraryStore.setLiked(song(2), true)
        LibraryStore.playlists.value.forEach { LibraryStore.deletePlaylist(it.id) }
        LibraryStore.createPlaylist("Road trip", song(3))
        LibraryStore.createPlaylist("Sunday", song(4))
        (6 downTo 1).forEach { History.record(song(it), 60_000) }
    }

    @Test fun libraryOpensOnLikedSongsWithASignInButtonWhenSignedOut() {
        fillLibrary()
        nav.goTab(Dest.Library)
        show { TvShell(nav) }
        compose.onNodeWithText("Your music").assertExists()
        compose.onNodeWithText("Liked songs").assertIsFocused()
        compose.onNodeWithText("Road trip").assertExists()
        scrollTo("Recently played")
        compose.onNodeWithText("Recently played").assertExists()
        scrollTo("Your music")
        save("tv-library-tab")
        // Down reaches the Sign in button under the first card, which goes to Settings.
        compose.onNodeWithText("Liked songs").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Sign in").assertIsFocused()
        compose.onNodeWithText("Sign in").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(Dest.Settings, nav.current)
    }

    @Test fun librarySignedInShowsTheAccountsPlaylists() {
        fillLibrary()
        setFlow(AccountStore, "_signedIn", true)
        setFlow(LibraryCache, "_playlists", (1..6).map { ShelfItem("Mix $it", "Playlist", "https://lh3.googleusercontent.com/m$it=w544-h544", null, "VLPL$it") })
        nav.goTab(Dest.Library)
        show { TvShell(nav) }
        compose.onNodeWithText("YouTube Music playlists").assertExists()
        compose.onNodeWithText("Mix 1").assertExists()
        compose.onNodeWithText("Liked songs").assertIsFocused()
        compose.onNodeWithText("Liked songs").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Mix 1").assertIsFocused()
        save("tv-library-signed-in")
    }

    @Test fun backFromAPlaylistLandsOnItsCard() {
        fillLibrary()
        nav.goTab(Dest.Library)
        show { TvShell(nav) }
        compose.onNodeWithText("Liked songs").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("Sunday").assertIsFocused()
        compose.onNodeWithText("Sunday").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(nav.current is Dest.Local)
        compose.onNodeWithText("1 song").assertExists()
        compose.runOnIdle { nav.back() }
        compose.waitForIdle()
        compose.onNodeWithText("Sunday").assertIsFocused()
    }

    // ---- Settings ----

    @Test fun settingsOpensOnSignInAndASwitchFlipsWithOk() {
        nav.goTab(Dest.Settings)
        show { TvShell(nav) }
        compose.onNodeWithText("Use your phone to bring your YouTube Music account here").assertIsFocused()
        save("tv-settings-top")
        val before = AppSettings.playback.value.losslessStreaming
        scrollTo("Lossless streaming")
        compose.onNodeWithText("Lossless streaming").requestFocus()
        compose.onNodeWithText("Lossless streaming").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(!before, AppSettings.playback.value.losslessStreaming)
        scrollTo("Automix")
        compose.onNodeWithText("Automix").requestFocus()
        save("tv-settings-switches")
    }

    @Test fun settingsSignedInShowsTheAccountAndPairedPhones() {
        setFlow(AccountStore, "_signedIn", true)
        setFlow(AccountStore, "_account", Account("Asha Rao", "asha@example.com", "https://lh3.googleusercontent.com/me=s128"))
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val remote = TvRemote(app, PairedPhones(MemoryStorage())).apply { phones.add("Pixel 8") }
        nav = TvNav(mutableStateListOf<Dest>(Dest.Settings), player, remote)
        show { TvShell(nav) }
        compose.onNodeWithText("Asha Rao").assertExists()
        compose.onNodeWithText("Sign out").assertExists()
        scrollTo("Pixel 8")
        compose.onNodeWithText("Pixel 8").assertExists()
        compose.onNodeWithText("Pair a phone").requestFocus()
        save("tv-settings-signed-in")
    }

    @Test fun signInShowsACodeAndBackReturnsToTheRow() {
        nav.goTab(Dest.Settings)
        show { TvShell(nav) }
        compose.onNodeWithText("Use your phone to bring your YouTube Music account here").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Sign in with your phone").assertExists()
        save("tv-signin-shell")
        // Back closes the code, not Settings, and the focus is back on Sign in.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(Dest.Settings, nav.current)
        compose.onNodeWithText("Use your phone to bring your YouTube Music account here").assertIsFocused()
    }

    @Test fun theSignInCodeDrawn() {
        show(art = false) {
            PhoneCodePanel(
                "Sign in with your phone",
                "On your phone, open NepTune, go to Settings, then Link a device, then TV, and scan this code. Both must be on the same Wi-Fi. It lasts two minutes.",
                "opentune://pair?h=192.168.1.20&p=41234&k=Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA", CodeState.Ready, {}, {},
            )
        }
        compose.onNodeWithText("Cancel").assertIsFocused()
        save("tv-signin")
    }

    @Test fun anExpiredCodeOffersANewOneWithTheFocusOnIt() {
        var asked = 0
        show(art = false) {
            PhoneCodePanel("Pair a phone", "That code ran out. Make a new one.", null, CodeState.Expired, { asked++ }, {})
        }
        compose.onNodeWithText("New code").assertIsFocused()
        save("tv-pair-expired")
        compose.onNodeWithText("New code").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, asked) }
    }

    @Test fun thePairCodeDrawnOnceItWorked() {
        show(art = false) {
            PhoneCodePanel(
                "Pair a phone",
                "Paired. Open NepTune on the phone, then Settings, Link a device, TV, to use it as a remote.",
                null, CodeState.Done, {}, {},
            )
        }
        compose.onNodeWithText("Done").assertIsFocused()
        save("tv-pair-done")
    }

    @Test fun songCountsReadNaturally() {
        assertEquals("1 song", songCount(1))
        assertEquals("12 songs", songCount(12))
        assertEquals("0 songs", songCount(0))
    }

    // ---- Helpers ----

    /** Scrolls the page (the one list that scrolls up and down) until [text] is in it. */
    private fun scrollTo(text: String) {
        val vertical = SemanticsMatcher("scrolls vertically") { SemanticsProperties.VerticalScrollAxisRange in it.config }
        compose.onNode(vertical).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private class MemoryStorage : RemoteStorage {
        private var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private fun setFlow(owner: Any, name: String, value: Any?) {
        val field = owner::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(owner) as MutableStateFlow<Any?>).value = value
    }

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
