package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.playback.AudioLevels
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The stage lights over a dark page while a bass line plays, a few frames apart, to build/screenshots/lights. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class StageLightsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun withTheMusic() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF121016))) {
                StageLights(Color(0xFFE0457B), playing = true, still = false)
            }
        }
        // A kick on every half second for a few seconds, queued to be heard from about now on.
        val rate = 44_100
        val frames = rate * 4
        AudioLevels.measure(frames, 2, rate) { i ->
            val t = (i / 2).toDouble() / rate
            val kick = if (t % 0.5 < 0.12) 0.9 else 0.15
            (kick * sin(2 * PI * 55 * t)).toFloat()
        }
        Thread.sleep(450)
        listOf(0, 1, 2, 3).forEach { n ->
            repeat(12) { compose.mainClock.advanceTimeBy(16); Thread.sleep(8) }
            save("lights/frame-$n")
        }
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
