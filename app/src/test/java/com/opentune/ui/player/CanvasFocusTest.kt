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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * While a Canvas plays on the cover, the controls slide down after a few seconds and leave
 * the title; a short swipe up brings them back without going on to the lyrics or queue.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CanvasFocusTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, bitmap).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    private fun show(canvas: String?, focus: Boolean = true) {
        val song = Song("test", "Test track", "Test artist", "https://covers.invalid/test.jpg", "3:00")
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.NotFound, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(playerStyle = PlayerStyle.CLASSIC, reduceAnimation = false, fullScreenCover = true, canvasFocus = focus),
            canvasVideo = canvas,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 30_000L }, { 60_000L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test fun controlsGoOverACanvasAndASwipeUpBringsThemBack() {
        show("https://canvas.invalid/loop.mp4")
        compose.onNodeWithContentDescription("Queue").assertExists()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithContentDescription("Queue").assertDoesNotExist()
        compose.onAllNodesWithText("Test track").onFirst().assertExists()

        // A short swipe up from the middle of the screen.
        compose.onRoot().performTouchInput {
            down(center)
            repeat(8) { moveBy(androidx.compose.ui.geometry.Offset(0f, -14f)); compose.mainClock.advanceTimeBy(16) }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithContentDescription("Queue").assertExists()
        // It stayed on the cover: no list came in.
        compose.onNodeWithTag("pane-cover-flight").assertDoesNotExist()
        compose.onAllNodesWithText("Test track").onFirst().assertExists()
    }

    @Test fun withoutACanvasOrWithTheSettingOffTheControlsStay() {
        show(null)
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithContentDescription("Queue").assertExists()
    }

    @Test fun theSettingTurnsItOff() {
        show("https://canvas.invalid/loop.mp4", focus = false)
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithContentDescription("Queue").assertExists()
    }
}
