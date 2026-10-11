package com.opentune.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            val ui = InterfaceSettings(design = DesignOptions(style, DesignMotion.FLUID), reduceAnimation = true)
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
