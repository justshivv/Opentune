package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PlayerBackground
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each animated backdrop, still, as a picture to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class BackdropShotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun setUp() = AppSettings.init(ApplicationProvider.getApplicationContext())

    private fun shot(style: PlayerBackground) {
        val cover = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            // Teal with an orange disc in the middle, as many single covers are.
            for (y in 0 until 120) for (x in 0 until 120) {
                val d = (x - 60) * (x - 60) + (y - 60) * (y - 60)
                setPixel(x, y, if (d < 38 * 38) 0xFFF07A1E.toInt() else 0xFF1E9AA8.toInt())
            }
        }
        val engine = FakeImageLoaderEngine.Builder().apply {
            intercept({ it is String && it.contains("cover") }, android.graphics.drawable.BitmapDrawable(compose.activity.resources, cover).asImage())
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
        compose.setContent {
            MaterialTheme(darkColorScheme(surface = Color(0xFF1E1410), primary = Color(0xFFE0A070), primaryContainer = Color(0xFF6B3A12),
                tertiary = Color(0xFFC9B26A), secondary = Color(0xFFB08A70), inversePrimary = Color(0xFF8A5030))) {
                PlayerBackdrop(style, "https://img/cover", Modifier.fillMaxSize(), animate = false)
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/backdrop-${style.name.lowercase()}.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mesh() = shot(PlayerBackground.MESH)
    @Test fun echo() = shot(PlayerBackground.ECHO_GLOW)
    @Test fun gradient() = shot(PlayerBackground.GRADIENT)
    @Test fun blur() = shot(PlayerBackground.BLUR)
}
