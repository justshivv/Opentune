package com.opentune.ui.recognize

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.recognize.Recognized
import com.opentune.data.recognize.Recognizer
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The song-finding page at rest, listening and with a result, to build/screenshots/recognize. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class RecognizeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.contains("cover") }, android.graphics.drawable.ColorDrawable(0xFFE0457B.toInt()).asImage())
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(compose.activity).components { add(engine) }.build())
    }

    @Test fun pages() {
        compose.mainClock.autoAdvance = false
        Recognizer.show(Recognizer.State.Idle)
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), tertiary = Color(0xFFB388FF))) {
                Surface(color = MaterialTheme.colorScheme.background) { RecognizeScreen(PaddingValues(), onPlay = {}, onBack = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        save("recognize/ready")
        Recognizer.show(Recognizer.State.Listening(0.7f, 3f))
        listOf(300L, 700L, 1200L).forEach { t ->
            compose.mainClock.advanceTimeBy(t)
            save("recognize/listening-$t")
        }
        Recognizer.show(Recognizer.State.Found(Recognized("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", "https://cover/1", "https://www.shazam.com/track/1")))
        compose.mainClock.advanceTimeBy(1_500)
        save("recognize/found")
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
