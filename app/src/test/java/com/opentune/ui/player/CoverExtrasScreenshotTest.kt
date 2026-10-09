package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.model.Song
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h560dp-xxhdpi", application = android.app.Application::class)
class CoverExtrasScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun glowAndSeekTaps() {
        val engine = FakeImageLoaderEngine.Builder().intercept({ it is String }, android.graphics.drawable.ColorDrawable(0xFF3A7BD5.toInt()).asImage()).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        val seeks = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFF7FB2FF), tertiary = Color(0xFF9EE7FF))) {
                Surface(color = Color(0xFF0B0B10)) {
                    ArtworkPane(
                        Song("v", "Song", "Artist", "https://img/c"), isPlaying = true, onSwipeNext = {}, onSwipePrevious = {},
                        modifier = Modifier.padding(40.dp).testTag("cover"),
                        onSeekBy = { seeks += it }, glow = true,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("cover").performTouchInput { doubleClick(centerRight.copy(x = width * 0.8f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("cover").performTouchInput { doubleClick(centerRight.copy(x = width * 0.8f)) }
        compose.mainClock.advanceTimeBy(160)
        assertEquals(listOf(10_000L, 10_000L), seeks)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/cover-extras.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
