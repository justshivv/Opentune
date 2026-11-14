package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
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

/**
 * The cover folding into the queue header and back, frame by frame: it moves one way only, right
 * through its last frame (it used to flash back to where it started), and the grab pill only drifts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CoverFoldMotionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 60)) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, bitmap).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    @Test fun foldAndUnfoldMoveOneWayAndThePillOnlyDrifts() {
        val songs = (1..6).map { Song("s$it", "Track $it", "Artist $it", "https://covers.invalid/$it.jpg", "3:00") }
        val state = PlayerUiState(
            song = songs[0], isPlaying = true, isSounding = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.NotFound, queue = songs, currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = "Quick picks",
            ui = InterfaceSettings(liquidGlass = false, playerStyle = PlayerStyle.APPLE_MUSIC, reduceAnimation = false, fullScreenCover = true),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 30_000L }, { 60_000L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Queue").performClick()
        check(frames(), shrinking = true)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(SemanticsMatcher("show cover") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == "Show the cover" }).performClick()
        check(frames(), shrinking = false)
    }

    private class Frame(val pill: Float?, val coverWidth: Float?)

    private fun frames(): List<Frame> = (0 until 40).map {
        compose.mainClock.advanceTimeByFrame()
        Frame(
            runCatching { compose.onNodeWithTag("grab-handle").fetchSemanticsNode().boundsInRoot.top }.getOrNull(),
            runCatching { compose.onNodeWithTag("pane-cover-flight").fetchSemanticsNode().boundsInRoot.width }.getOrNull(),
        )
    }

    private fun check(frames: List<Frame>, shrinking: Boolean) {
        val widths = frames.mapNotNull { it.coverWidth }
        assertTrue("the cover flies", widths.size > 10 && widths.first() != widths.last())
        widths.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("cover went back at frame $i: $a -> $b", if (shrinking) b <= a + 0.5f else b >= a - 0.5f)
        }
        val pills = frames.mapNotNull { it.pill }
        assertTrue("the pill is there throughout", pills.size == frames.size)
        pills.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("pill jumped at frame $i: $a -> $b", kotlin.math.abs(b - a) < 2.5f)
        }
        assertTrue("the pill drifts", kotlin.math.abs(pills.last() - pills.first()) > 3f)
    }
}
