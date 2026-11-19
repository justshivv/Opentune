package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
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
import java.io.File
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
 * The pill between the Lyrics and Queue buttons keeps clear of them on narrow phones: it was
 * 230 dp of buttons, filled all the room between them, and ran into a lit Lyrics button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PillRowFitTest {
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

    @Config(qualifiers = "w320dp-h700dp-xhdpi") @Test fun appleOnAVeryNarrowPhone() = fits(PlayerStyle.APPLE_MUSIC, "apple-320")
    @Config(qualifiers = "w360dp-h800dp-xxhdpi") @Test fun appleOnANarrowPhone() = fits(PlayerStyle.APPLE_MUSIC, "apple-360")
    @Config(qualifiers = "w360dp-h800dp-xxhdpi") @Test fun classicOnANarrowPhone() = fits(PlayerStyle.CLASSIC, "classic-360")

    private fun fits(style: PlayerStyle, name: String) {
        val song = Song("test", "Let It Happen", "Tame Impala", "https://covers.invalid/test.jpg", "7:47")
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 467_000,
            lyrics = LyricsState.NotFound, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(playerStyle = style, reduceAnimation = true, commentsButton = true),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 126_000L }, { 200_000L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val lyrics = compose.onNodeWithContentDescription("Lyrics").fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithContentDescription("Queue").fetchSemanticsNode().boundsInRoot
        val buttons = buildList {
            if (style != PlayerStyle.APPLE_MUSIC) add("Sleep timer")
            add("Audio output"); add("Comments")
            if (style == PlayerStyle.APPLE_MUSIC) add("Listen together")
        }.map { compose.onNodeWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        save(name)
        val left = buttons.minOf { it.left }
        val right = buttons.maxOf { it.right }
        // A clear gap either side, so the pill reads apart from a lit Lyrics or Queue button.
        val gap = with(compose.density) { 8.dp.toPx() }
        assertTrue("the pill (from $left) runs into the Lyrics button (to ${lyrics.right})", left - lyrics.right >= gap)
        assertTrue("the pill (to $right) runs into the Queue button (from ${queue.left})", queue.left - right >= gap)
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/pill-row/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
