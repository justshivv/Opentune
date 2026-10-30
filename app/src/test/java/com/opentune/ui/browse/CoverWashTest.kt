package com.opentune.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.materialkolor.hct.Hct
import com.opentune.data.settings.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An album or playlist page's colours: never plain black for a dark cover, and no line where the header ends. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CoverWashTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() = AppSettings.init(ApplicationProvider.getApplicationContext())

    @Test fun darkAndColourlessCoversGetAToneThatReads() {
        // A black-and-white cover has no colour to read: a mid grey, not black.
        val grey = Hct.fromInt(coverTone(null, dark = true).toArgbCompat())
        assertTrue("grey tone ${grey.tone}", grey.tone >= 41.0 && grey.chroma < 2.0)
        // A near-black brown is lifted to a mid tone and keeps its hue.
        val brown = Color(0xFF2A1A10)
        val lifted = Hct.fromInt(coverTone(brown, dark = true).toArgbCompat())
        assertTrue("lifted tone ${lifted.tone}", lifted.tone >= 41.0)
        assertTrue("hue kept", kotlin.math.abs(lifted.hue - Hct.fromInt(brown.toArgbCompat()).hue) < 12)
        // A bright cover stays as it is, within the range.
        assertTrue(Hct.fromInt(coverTone(Color(0xFFE08A2A), dark = true).toArgbCompat()).tone <= 63.0)
    }

    @Test fun theHeaderMeltsIntoTheWashWithNoLine() {
        val tint = Color(0xFF2B2B2D)
        compose.setContent {
            MaterialTheme(androidx.compose.material3.darkColorScheme(background = Color.Black, surface = Color.Black)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().coverWash(tint)) {
                        // A header much lighter than the wash, ending two thirds down where the seam used to show.
                        Box(Modifier.fillMaxWidth().height(590.dp).fadeOutAtFoot().background(Color(0xFF6A6A6E)))
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/screenshots/browse/cover-wash.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val x = bitmap.width / 2
        fun lum(y: Int) = Color(bitmap.getPixel(x, y)).luminance()
        // The biggest change in brightness from one row to the next, down the middle of the page.
        var jump = 0f
        for (y in 1 until bitmap.height) jump = maxOf(jump, kotlin.math.abs(lum(y) - lum(y - 1)))
        assertTrue("brightest row-to-row step $jump", jump < 0.01f)
        // And the foot of the page isn't plain black.
        assertTrue("foot ${lum(bitmap.height - 2)}", lum(bitmap.height - 2) > 0.008f)
    }

    private fun Color.toArgbCompat(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
