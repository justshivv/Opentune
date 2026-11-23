package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.innertube.VideoQuality
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.PlayerTheme
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

/** The music video on the whole screen: its controls, double-tap seeks, the quality list and the ways out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = Application::class)
class VideoFullScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
    }

    private val song = Song("x", "Midnight City", "M83", null, "4:03")

    private fun state(playing: Boolean = true, reduce: Boolean = true) = PlayerUiState(
        song = song, isPlaying = playing, isSounding = playing, isBuffering = false, hasNext = true, shuffle = false,
        repeatMode = Player.REPEAT_MODE_OFF, durationMs = 243_000, lyrics = LyricsState.NotFound, queue = listOf(song),
        currentIndex = 0, upNext = emptyList(), sound = SoundSettings(), theme = ThemeSettings(),
        ui = InterfaceSettings(reduceAnimation = reduce), videoMode = true, videoAvailable = true,
    )

    /** A 16:9 frame in strong colours standing in for the picture. */
    private fun frame(): Bitmap {
        val w = 1280; val h = 720
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1A2A6C.toInt(), 0xFFB21F1F.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFFFDBB2D.toInt()
        c.drawCircle(w * 0.7f, h * 0.38f, h * 0.18f, p)
        p.color = 0xFF0B0B10.toInt()
        for (i in 0 until 9) c.drawRect(w * (0.05f + i * 0.1f), h * (0.62f - (i % 4) * 0.06f), w * (0.12f + i * 0.1f), h.toFloat(), p)
        return b
    }

    private fun picture(bitmap: Bitmap): @Composable BoxScope.(Boolean) -> Unit = { crop ->
        Box(Modifier.matchParentSize().clipToBounds().testTag(if (crop) "picture-fill" else "picture-fit"), contentAlignment = Alignment.Center) {
            Image(
                bitmap.asImageBitmap(), null,
                if (crop) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f),
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }

    private fun actions(seeks: MutableList<Long> = mutableListOf()) =
        PlayerActions({}, {}, {}, {}, {}, { seeks += it }, {}, {}, {})

    @Test fun controlsShowOverThePicture() {
        val bitmap = frame()
        compose.setContent {
            PlayerTheme(seed = null, settings = ThemeSettings()) {
                FullScreenVideo(
                    state(), position = { 92_000 }, buffered = { 140_000 }, actions = actions(),
                    page = VideoPage(picture(bitmap), aspect = 16f / 9f, playing = 720, chosen = VideoQuality.AUTO),
                    entry = FullScreenEntry.ROTATION, onExit = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Midnight City").assertIsDisplayed()
        compose.onNodeWithText("M83").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed()
        compose.onNodeWithText("Auto").assertIsDisplayed()
        compose.onNodeWithText("1:32").assertIsDisplayed()
        shoot("video-full-screen")
    }

    @Test fun doubleTapOnTheSidesSeeksTenSeconds() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                FullScreenVideo(
                    state(), position = { 60_000 }, buffered = { 0 }, actions = actions(seeks),
                    page = VideoPage(picture(frame())), entry = FullScreenEntry.ROTATION, onExit = {},
                    controlsAtFirst = false,
                )
            }
        }
        val page = compose.onNodeWithTag("video-full-screen")
        page.performTouchInput { doubleClick(Offset(width * 0.1f, height * 0.5f)) }
        compose.waitForIdle()
        compose.onNodeWithText("10 s").assertIsDisplayed()
        page.performTouchInput { doubleClick(Offset(width * 0.9f, height * 0.5f)) }
        compose.waitForIdle()
        assertEquals(listOf(50_000L, 70_000L), seeks)
        shoot("video-full-screen-seek")
    }

    @Test fun aTapBringsTheControlsAndTheyGoAfterAFewSeconds() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                FullScreenVideo(
                    state(), position = { 0 }, buffered = { 0 }, actions = actions(),
                    page = VideoPage(picture(frame())), entry = FullScreenEntry.ROTATION, onExit = {},
                    controlsAtFirst = false,
                )
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Midnight City").assertDoesNotExist()
        compose.onNodeWithTag("video-full-screen").performTouchInput { click(Offset(width * 0.5f, height * 0.5f)) }
        // Past the double-tap wait, the single tap lands.
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Midnight City").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(VIDEO_CONTROLS_MS + 500)
        compose.onNodeWithText("Midnight City").assertDoesNotExist()
    }

    @Test fun pickingASizeAsksForItAndTheButtonSwitchesFitAndFill() {
        var picked: Int? = null
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                var chosen by remember { mutableStateOf(VideoQuality.AUTO) }
                FullScreenVideo(
                    state(playing = false), position = { 0 }, buffered = { 0 }, actions = actions(),
                    page = VideoPage(
                        picture(frame()), aspect = 16f / 9f, playing = 720, chosen = chosen,
                        choices = { listOf(VideoQuality.AUTO, 1080, 720, 360) },
                        choose = { picked = it; chosen = it },
                    ),
                    entry = FullScreenEntry.ROTATION, onExit = {},
                )
            }
        }
        compose.onNodeWithTag("video-quality").performClick()
        // The size playing is shown with Auto.
        compose.onNodeWithText("Auto · 720p").assertIsDisplayed()
        compose.onNodeWithText("1080p").performClick()
        compose.runOnIdle { assertEquals(1080, picked) }
        compose.onNodeWithText("1080p").assertIsDisplayed()

        compose.onNodeWithTag("picture-fit").assertIsDisplayed()
        compose.onNodeWithText("Fit").performClick()
        compose.onNodeWithTag("picture-fill").assertIsDisplayed()
    }

    @Test fun aSwipeDownOrTheButtonLeaves() {
        var exits = 0
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                FullScreenVideo(
                    state(playing = false), position = { 0 }, buffered = { 0 }, actions = actions(),
                    page = VideoPage(picture(frame())), entry = FullScreenEntry.ROTATION, onExit = { exits++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
        compose.onNodeWithTag("video-full-screen").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.95f) }
        compose.runOnIdle { assertEquals(2, exits) }
    }

    @Test fun theButtonTurnsTheScreenForAWidePictureAndGivesItBack() {
        val activity = compose.activity
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var open by mutableStateOf(true)
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                if (open) FullScreenVideo(
                    state(playing = false), position = { 0 }, buffered = { 0 }, actions = actions(),
                    page = VideoPage(picture(frame()), aspect = 16f / 9f), entry = FullScreenEntry.BUTTON, onExit = { open = false },
                )
            }
        }
        compose.waitForIdle()
        assertTrue(
            activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
        )
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        compose.waitForIdle()
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
