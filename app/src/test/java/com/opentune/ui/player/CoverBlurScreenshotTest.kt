package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PlayerBackground
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Cover blur backdrop, to build/screenshots/player: a cover with a hard edge comes out as a soft fade. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CoverBlurScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() = AppSettings.init(ApplicationProvider.getApplicationContext())

    @Test fun aHardEdgedCoverBlursSmooth() {
        // Orange over near-black, split hard down the middle.
        val cover = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 240) for (x in 0 until 240) setPixel(x, y, if (y < 120) 0xFFE0842A.toInt() else 0xFF161210.toInt())
        }
        val engine = FakeImageLoaderEngine.Builder().apply {
            intercept({ it is String && it.contains("cover") }, android.graphics.drawable.BitmapDrawable(compose.activity.resources, cover).asImage())
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        compose.setContent {
            MaterialTheme(darkColorScheme(surface = Color(0xFF1A1110), primaryContainer = Color(0xFF6B3A12))) {
                PlayerBackdrop(PlayerBackground.BLUR, "https://img/cover", Modifier.fillMaxSize(), animate = false)
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/cover-blur.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val x = bitmap.width / 2
        fun lum(y: Int) = Color(bitmap.getPixel(x, y)).luminance()
        var jump = 0f
        for (y in 1 until bitmap.height) jump = maxOf(jump, kotlin.math.abs(lum(y) - lum(y - 1)))
        // The cover's hard edge is gone: no step anywhere down the screen (an edge reads about 0.013).
        assertTrue("brightest row-to-row step $jump", jump < 0.007f)
        // The dark half still carries some of the song's tone rather than going black.
        assertTrue("lower third ${lum(bitmap.height * 2 / 3)}", lum(bitmap.height * 2 / 3) > 0.004f)
    }
}
