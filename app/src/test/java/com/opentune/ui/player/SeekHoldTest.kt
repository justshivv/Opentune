package com.opentune.ui.player

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Let go of the bar and the line stays there while the player catches up: no snap back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SeekHoldTest {
    @get:Rule val compose = createComposeRule()

    private fun shown(): Float = compose.onNodeWithContentDescription("Playback position", substring = true)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun lineStaysWhereTheFingerLetGoUntilTheSeekLands() {
        val at = mutableLongStateOf(30_000L)
        var asked = -1L
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                SeekBar({ at.longValue }, { 0L }, 200_000L, onSeek = { asked = it }, modifier = Modifier.width(400.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Playback position", substring = true).performTouchInput {
            swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY), 300)
        }
        compose.mainClock.advanceTimeBy(48)
        assertEquals(0.8f, asked / 200_000f, 0.03f)
        // The player hasn't moved yet: the bar still shows where the finger let go.
        assertEquals(asked / 200_000f, shown(), 0.001f)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(asked / 200_000f, shown(), 0.001f)
        // The player gets there: the bar goes on from the player's own position, no jump.
        at.longValue = asked + 50
        compose.mainClock.advanceTimeBy(100)
        assertEquals(asked / 200_000f, shown(), 0.002f)
    }
}
