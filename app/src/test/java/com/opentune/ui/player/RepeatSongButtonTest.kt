package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The circle that repeats the playing song: on the queue page beside the modes pill, not inside it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RepeatSongButtonTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var toggles = 0

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null; PlayerMorph.lastPlayerCover = null; PlayerMorph.flying = false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder().default(BitmapDrawable(context.resources, bitmap).asImage()).build())
        }.coroutineContext(Dispatchers.Unconfined).build())
    }

    private fun show(repeat: Int) {
        val song = Song("test", "Test track", "Test artist", "https://covers.invalid/test.jpg", "3:00")
        val state = PlayerUiState(
            song = song, isPlaying = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = repeat, durationMs = 180_000,
            lyrics = LyricsState.NotFound, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(liquidGlass = false, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 30_000L }, { 60_000L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, toggleRepeatOne = { toggles++ }))
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File("build/screenshots/player/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun itIsOnTheQueuePageBesideTheModesAndRepeatsTheSong() {
        show(Player.REPEAT_MODE_OFF)
        // Not on the cover page, where the sleep and output pill is.
        compose.onNodeWithContentDescription("Repeat this song").assertDoesNotExist()
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Repeat this song").assertIsDisplayed()
        // The pill still has its own three.
        compose.onNodeWithContentDescription("Shuffle off").assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat off").assertIsDisplayed()
        compose.onNodeWithContentDescription("AutoPlay").assertIsDisplayed()
        shot("repeat-song-off")
        compose.onNodeWithContentDescription("Repeat this song").performClick()
        assertEquals(1, toggles)
    }

    @Test fun itIsLitWhileTheSongRepeats() {
        show(Player.REPEAT_MODE_ONE)
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Repeating this song").assertIsDisplayed()
        shot("repeat-song-on")
        compose.onNodeWithContentDescription("Repeating this song").performClick()
        assertEquals(1, toggles)
    }
}
