package com.opentune.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SeekbarThickness
import com.opentune.data.settings.SeekbarTime
import com.opentune.data.settings.SeekbarThumb
import com.opentune.data.settings.SeekbarColor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SeekBarTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun thickLineHandleMeetsTheFillWithoutARoundedShoulder() {
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(seekbarThickness = SeekbarThickness.THICK,
            seekbarThumb = SeekbarThumb.LINE, seekbarColor = SeekbarColor.PLAIN,
            seekbarTime = SeekbarTime.HIDDEN) }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(onSurface = Color.Red)) {
                Surface(color = Color.Black) { SeekBar({ 50_000L }, { 100_000L }, 100_000L, onSeek = {}) }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithContentDescription("Playback position. Drag away from the bar for fine scrubbing.")
            .fetchSemanticsNode().boundsInRoot
        // Draw directly under Robolectric: PixelCopy waits for a hardware frame
        // callback that its simulated window does not deliver.
        fun composeView(view: android.view.View): android.view.View? {
            if (view.javaClass.simpleName == "AndroidComposeView") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                composeView(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val view = requireNotNull(composeView(compose.activity.window.decorView))
        val frame = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(frame))
        val density = compose.activity.resources.displayMetrics.density
        // Just outside the 3dp handle, at the track's top edge. A rounded
        // progress capsule leaves this pixel translucent instead of filled.
        val x = (bounds.center.x - 2.25f * density).toInt()
        val y = (bounds.center.y - 4.5f * density + .4f * density).toInt()
        assertTrue("fill must reach the line handle at full track height", android.graphics.Color.red(frame.getPixel(x, y)) > 242)
        frame.recycle()
    }

    @Test fun thickTrackTapsAndDragsNeverOpenQualityWithTimesShownOrHidden() {
        AppSettings.init(compose.activity)
        var sought = -1L
        var qualityClicks = 0
        compose.setContent {
            MaterialTheme {
                SeekBar({ 20_000L }, { 60_000L }, 100_000L, onSeek = { sought = it }, quality = {
                    TextButton(onClick = { qualityClicks++ }) { Text("Quality") }
                })
            }
        }
        for (time in listOf(SeekbarTime.LEFT, SeekbarTime.HIDDEN)) {
            compose.runOnIdle {
                sought = -1L
                qualityClicks = 0
                AppSettings.updateUi { it.copy(seekbarThickness = SeekbarThickness.THICK, seekbarTime = time) }
            }
            val bar = compose.onNodeWithContentDescription("Playback position. Drag away from the bar for fine scrubbing.")
            bar.assertHeightIsAtLeast(48.dp)
            bar.performTouchInput { click(Offset(centerX, bottom - 1f)) }
            compose.runOnIdle {
                assertTrue(sought in 49_000L..51_000L)
                assertEquals(0, qualityClicks)
            }
            bar.performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY)) }
            compose.runOnIdle {
                assertTrue(sought > 60_000L)
                assertEquals(0, qualityClicks)
            }
            compose.onNodeWithText("Quality").performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, qualityClicks) }
        }
    }
}
