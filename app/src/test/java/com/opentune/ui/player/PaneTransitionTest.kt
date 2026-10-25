package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.*
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PaneTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, bitmap).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    @Test fun classicLyricsShrinksBeforeReachingTheHeader() = transition(PlayerStyle.CLASSIC, "Lyrics")
    @Test fun classicQueueShrinksBeforeReachingTheHeader() = transition(PlayerStyle.CLASSIC, "Queue")
    @Test fun appleLyricsShrinksBeforeReachingTheHeader() = transition(PlayerStyle.APPLE_MUSIC, "Lyrics")
    @Test fun appleQueueShrinksBeforeReachingTheHeader() = transition(PlayerStyle.APPLE_MUSIC, "Queue")
    @Test fun fullClassicCoverShrinksBeforeReachingTheHeader() = transition(PlayerStyle.CLASSIC, "Lyrics", true)

    private fun transition(style: PlayerStyle, pane: String, fullCover: Boolean = false) {
        val song = Song("test", "Test track", "Test artist", "https://covers.invalid/test.jpg", "3:00")
        val state = PlayerUiState(
            song = song, isPlaying = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.NotFound, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(liquidGlass = false, playerStyle = style, reduceAnimation = false, fullScreenCover = fullCover),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 30_000L }, { 60_000L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription(pane).performClick()
        compose.mainClock.advanceTimeByFrame()
        val start = compose.onNodeWithTag("pane-cover-flight").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(96)
        val middle = compose.onNodeWithTag("pane-cover-flight").fetchSemanticsNode().boundsInRoot
        assertTrue("Cover must move during the transition instead of appearing in the corner later", middle.width < start.width * .95f)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("pane-cover-flight").assertDoesNotExist()
        compose.onNodeWithContentDescription(pane).performClick()
        compose.mainClock.advanceTimeByFrame()
        val returning = compose.onNodeWithTag("pane-cover-flight").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(96)
        val grown = compose.onNodeWithTag("pane-cover-flight").fetchSemanticsNode().boundsInRoot
        assertTrue("Cover must grow back continuously", grown.width > returning.width * 1.05f)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("pane-cover-flight").assertDoesNotExist()
    }
}
