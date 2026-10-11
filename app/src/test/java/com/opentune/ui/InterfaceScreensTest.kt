package com.opentune.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import com.opentune.data.settings.*
import com.opentune.ui.browse.SongActions
import com.opentune.ui.library.LibraryNav
import com.opentune.ui.library.LibraryScreen
import com.opentune.ui.player.*
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class InterfaceScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        // Glass helpers read the shared settings as well as the player state.
        AppSettings.updateUi { it.copy(liquidGlass = false, reduceBlur = true, reduceAnimation = true) }
        LibraryStore.init(context)
        History.init(context)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, coverArt()).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    @Test fun fullPlayerRendersEachOptionalDesignWithoutChangingItsControls() {
        var style by mutableStateOf(InterfaceDesign.FLOW)
        val song = Song("test", "After the rain", "The night garden", "https://covers.invalid/test.jpg", "3:00")
        compose.setContent {
            // Native software capture cannot run the existing GPU refraction shader.
            val ui = InterfaceSettings(design = DesignOptions(style, DesignMotion.FLUID), reduceAnimation = true, liquidGlass = false)
            val theme = ThemeSettings(mode = ThemeMode.DARK, pureBlack = false)
            OpenTuneTheme(theme, ui = ui) {
                PlayerLayout(PlayerUiState(song, false, false, true, false, Player.REPEAT_MODE_OFF, 180_000L,
                    LyricsState.Loading, listOf(song), 0, emptyList(), SoundSettings(), theme, ui),
                    position = { 64_000L }, buffered = { 120_000L }, actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        InterfaceDesign.entries.filter { it != InterfaceDesign.CURRENT }.forEach { option ->
            compose.runOnIdle { style = option }
            compose.onNodeWithText("After the rain").assertExists()
            compose.onNodeWithContentDescription("Play", substring = false).assertExists()
            save("${option.name.lowercase()}-full-player")
        }
    }

    @Test fun libraryFiltersKeepAllShortcutsAndEmptyStatesReachable() {
        compose.setContent {
            val ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW, DesignMotion.FLUID), reduceAnimation = true)
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.LIGHT, pureBlack = false), ui = ui) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}),
                        LibraryNav({}, {}, {}, {}, {}, {}, {}, {}, {}))
                }
            }
        }
        compose.onNodeWithText("Liked", substring = false).assertExists()
        save("flow-library-overview")
        compose.onNode(hasText("Playlists") and hasClickAction()).performClick()
        compose.onNodeWithText("New", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Import", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Recent", substring = false).performClick()
        compose.onNodeWithText("Your recently played songs will appear here.").assertIsDisplayed()
        compose.onNodeWithText("Overview", substring = false).performClick()
        compose.onNodeWithText("Downloads", substring = false).assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun flowPlayerKeepsTransportAndPaneNavigationReachableOnASmallerScreen() {
        val song = Song("test", "After the rain", "The night garden", "https://covers.invalid/test.jpg", "3:00")
        var played = 0
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.2f)) {
                val ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW, DesignMotion.FLUID), reduceAnimation = true, liquidGlass = false)
                val theme = ThemeSettings(mode = ThemeMode.DARK, pureBlack = false)
                OpenTuneTheme(theme, ui = ui) {
                    PlayerLayout(PlayerUiState(song, false, false, true, false, Player.REPEAT_MODE_OFF, 180_000L,
                        LyricsState.Loading, listOf(song), 0, emptyList(), SoundSettings(), theme, ui),
                        position = { 64_000L }, buffered = { 120_000L }, actions = PlayerActions({ played++ }, {}, {}, {}, {}, {}, {}, {}, {}))
                }
            }
        }
        compose.onNodeWithContentDescription("Play", substring = false).assertIsDisplayed().performClick()
        assertEquals(1, played)
        save("flow-player-small-large-text")
        compose.onNode(hasText("Queue") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Play", substring = false).assertIsDisplayed()
        save("flow-player-queue")
        compose.onNode(hasText("Lyrics") and hasClickAction()).performClick().assertIsSelected()
        compose.onNode(hasText("Playing") and hasClickAction()).performClick().assertIsSelected()
    }

    @Test fun flowHomeHasWorkingFeedModesAndAnHonestColdStart() {
        var populated by mutableStateOf(true)
        var hasHistory by mutableStateOf(true)
        var openedCollection: String? = null
        val song = Song("test", "After the rain", "The night garden", "https://covers.invalid/test.jpg", "3:00")
        var plays = 0
        val actions = SongActions(null, false, { _, _, _, _ -> plays++ }, {}, {})
        compose.setContent {
            val ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW, DesignMotion.FLUID), reduceAnimation = true, liquidGlass = false)
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, pureBlack = false), ui = ui) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    com.opentune.ui.home.HomeFeed(PaddingValues(bottom = 168.dp), actions, { openedCollection = it.browseId }, {}, {}, ui,
                        if (populated) UiState.Success(listOf(HomeShelf("In your rotation", listOf(ShelfItem("Night drive", "A little further", "https://covers.invalid/mix.jpg", videoId = null, browseId = "mix"))))) else UiState.Success(emptyList()),
                        emptyList(), if (populated && hasHistory) listOf(song) else emptyList(), emptyList())
                    BottomChrome(
                        inline = false,
                        tabs = listOf(ChromeTab("Home", Icons.Outlined.Home), ChromeTab("Explore", Icons.Outlined.Explore),
                            ChromeTab("Podcasts", Icons.Outlined.Podcasts), ChromeTab("Library", Icons.Outlined.LibraryMusic)),
                        selected = 0, onSelect = {}, onExpand = {}, searchSelected = false, onSearch = {},
                        mini = if (hasHistory) { { inline, modifier ->
                            MiniPlayer(song, true, false, true, { .36f }, {}, {}, {}, {}, modifier, inline)
                        } } else null,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Listen.").assertIsDisplayed()
        save("flow-actual-home")
        compose.onNodeWithContentDescription("Play", substring = false).performClick()
        assertEquals(1, plays)
        compose.onNodeWithText("Mixes", substring = false).performClick()
        compose.onNodeWithText("Your daily mixes grow with every listen.").assertIsDisplayed()
        compose.onNodeWithText("Recents", substring = false).performClick()
        compose.onNodeWithText("Jump back in").assertIsDisplayed()
        compose.onNodeWithText("For you", substring = false).performClick()
        compose.runOnIdle { hasHistory = false }
        compose.onNodeWithContentDescription("Open collection").assertIsDisplayed().performClick()
        assertEquals("mix", openedCollection)
        save("flow-first-launch-recommendations")
        compose.runOnIdle { populated = false }
        compose.onNodeWithText("Your soundtrack starts here").assertIsDisplayed()
        save("flow-cold-start-home")
    }

    @Test fun flowSearchShowsDiscoveryAndCanRecognizeNearbyMusic() {
        var recognized = 0
        compose.setContent {
            val ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW), reduceAnimation = true, liquidGlass = false)
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.LIGHT, pureBlack = false), ui = ui) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    com.opentune.ui.search.SearchScreen(PaddingValues(bottom = 24.dp), null, false, {}, {}, {}, {}, { recognized++ })
                }
            }
        }
        compose.onNodeWithText("Deep focus").assertIsDisplayed()
        save("flow-actual-search")
        compose.onNodeWithText("Name a song playing nearby").performScrollTo().performClick()
        assertEquals(1, recognized)
    }

    @Test fun flowDiscoverySubmitsRealSearchTerms() {
        var query = ""
        compose.setContent {
            OpenTuneTheme(ThemeSettings(), ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW))) {
                com.opentune.ui.search.FlowDiscovery({ query = it })
            }
        }
        compose.onNodeWithText("Deep focus").performClick()
        assertEquals("instrumental ambient focus", query)
        compose.onNodeWithText("After hours").performClick()
        assertEquals("late night jazz", query)
    }

    @Test fun flowSettingsGroupsCustomizationAndKeepsSettingsSearch() {
        compose.setContent {
            val ui = InterfaceSettings(design = DesignOptions(InterfaceDesign.FLOW), reduceAnimation = true, liquidGlass = false)
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.LIGHT, pureBlack = false), ui = ui) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    com.opentune.ui.settings.SettingsScreen(PaddingValues(bottom = 24.dp), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Make it yours").assertIsDisplayed()
        save("flow-actual-settings")
        compose.onNodeWithText("Search settings").performTextInput("Dock animation")
        compose.onNode(hasText("Dock animation") and !hasSetTextAction()).assertIsDisplayed()
    }

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = File("build/screenshots/interface-design/$name.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
