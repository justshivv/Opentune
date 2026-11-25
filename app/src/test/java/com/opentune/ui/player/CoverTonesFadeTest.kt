package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A new song's cover colours ease into the player's backdrop on every frame, without the
 * backdrop being composed again on each of them, and without a jump along the way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CoverTonesFadeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun images() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun solid(c: Int) = BitmapDrawable(context.resources, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }).asImage()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(FakeImageLoaderEngine.Builder()
                .intercept({ it is String && it.contains("tones-red") }, solid(android.graphics.Color.rgb(200, 30, 40)))
                .intercept({ it is String && it.contains("tones-blue") }, solid(android.graphics.Color.rgb(30, 60, 210)))
                .build())
        }.build())
    }

    /** Stands for the backdrop: composed only when what it's given changes; reads the tones each frame, as drawing does. */
    @Composable private fun Backdrop(tones: FadingCoverTones, composed: IntArray, drawn: MutableList<Color>) {
        SideEffect { composed[0]++ }
        androidx.compose.runtime.LaunchedEffect(tones) {
            repeat(300) { androidx.compose.runtime.withFrameNanos { drawn += tones.now().deep } }
        }
    }

    @Test fun fadeRedrawsWithoutComposing() {
        val url = mutableStateOf("https://img/tones-red.jpg")
        val composed = intArrayOf(0)
        val drawn = mutableListOf<Color>()
        compose.setContent {
            MaterialTheme {
                val tones = rememberCoverTones(url.value)
                Backdrop(tones, composed, drawn)
            }
        }
        // The first cover read and settled.
        runCatching { compose.waitUntil(10_000) { compose.runOnIdle { drawn.lastOrNull() }?.let { it.red > it.blue } == true } }
            .onFailure { throw AssertionError("first cover: ${drawn.size} frames, last ${drawn.takeLast(3)}, composed ${composed[0]}", it) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val before = composed[0]
        drawn.clear()
        compose.runOnIdle { url.value = "https://img/tones-blue.jpg" }
        runCatching { compose.waitUntil(10_000) { compose.runOnIdle { drawn.lastOrNull() }?.let { it.blue > it.red } == true } }
            .onFailure { throw AssertionError("second cover: ${drawn.size} frames, last ${drawn.takeLast(3)}, composed ${composed[0]}", it) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val frames = drawn.distinct()
        assertTrue("composed ${composed[0] - before} times for one change of cover", composed[0] - before <= 2)
        assertTrue("the colours eased over several frames: ${frames.size}", frames.size > 5)
        val steps = frames.zipWithNext { a, b -> kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.blue - b.blue) }
        assertTrue("a jump in the fade: $steps", steps.max() < 0.2f)
    }
}
