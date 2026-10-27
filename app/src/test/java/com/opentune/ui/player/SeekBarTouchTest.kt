package com.opentune.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.SeekbarThickness
import com.opentune.data.settings.SeekbarTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SeekBarTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
