package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.settings.PlayerBackground
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The gradient player background with its drifting lights, a few seconds apart, to build/screenshots/ambient. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class AmbientLightsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun drifts() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(
                darkColorScheme(
                    primary = Color(0xFF7FB2FF),
                    tertiary = Color(0xFF9EE7FF),
                    secondary = Color(0xFF4C7BD9),
                    inversePrimary = Color(0xFF2E5FA8),
                    primaryContainer = Color(0xFF0B2350),
                    surfaceContainer = Color(0xFF0A1530),
                    surface = Color(0xFF060B18),
                ),
            ) {
                PlayerBackdrop(PlayerBackground.GRADIENT, null, Modifier.fillMaxSize(), animate = true, playing = true)
            }
        }
        var at = 0L
        listOf(500L, 4_000L, 9_000L).forEach { t ->
            compose.mainClock.advanceTimeBy(t - at)
            at = t
            save("ambient/${"%05d".format(t)}")
        }
    }

    @Test fun behindLyrics() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65), tertiary = Color(0xFFB388FF), secondary = Color(0xFFE0457B), inversePrimary = Color(0xFF8E44AD), primaryContainer = Color(0xFF5A2A10), surface = Color(0xFF120C10))) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(androidx.compose.ui.Modifier.background(MaterialTheme.colorScheme.surface))) {
                    AmbientLights(listOf(Color(0xFFFF8A65), Color(0xFFB388FF), Color(0xFFE0457B), Color(0xFF8E44AD), Color(0xFF5A2A10)), playing = true, animate = true, modifier = Modifier.fillMaxSize(), field = true)
                    androidx.compose.material3.Text(
                        "This is the line being sung\nand this is the next one",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        save("ambient/lyrics")
    }

    private fun save(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
