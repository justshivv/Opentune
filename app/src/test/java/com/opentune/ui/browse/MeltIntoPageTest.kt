package com.opentune.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A list page's cover melts into what is behind the page: no edge where it ends. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class MeltIntoPageTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun coverFadesIntoTheColourBehind() {
        val behind = Color(0xFF2050C0)
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(400.dp).background(behind)) {
                Box(Modifier.fillMaxSize().meltIntoPage().background(Color.Red))
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val heightPx = (400 * compose.activity.resources.displayMetrics.density).toInt()
        val x = bmp.width / 2
        fun red(y: Int) = android.graphics.Color.red(bmp.getPixel(x, y)) / 255f
        fun blue(y: Int) = android.graphics.Color.blue(bmp.getPixel(x, y)) / 255f
        // The cover in full at the top, the page's own colour at the foot.
        assertTrue("top ${red(heightPx / 10)}", red(heightPx / 10) > 0.95f && blue(heightPx / 10) < 0.05f)
        val foot = heightPx - 1
        assertTrue("foot ${red(foot)} ${blue(foot)}", kotlin.math.abs(red(foot) - behind.red) < 0.02f && kotlin.math.abs(blue(foot) - behind.blue) < 0.02f)
        // And no step on the way down: each row changes only a little from the one above.
        var last = red(0)
        for (y in 1 until heightPx) {
            val r = red(y)
            assertTrue("step at $y: $last -> $r", kotlin.math.abs(r - last) < 0.02f)
            last = r
        }
    }
}
