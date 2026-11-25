package com.opentune.ui.player

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A queue row dropped part-way between two places settles into its place from where the
 * finger left it, and the rows keep their place in the list (and so aren't made again)
 * once the player has moved the song, rather than every row after the move blinking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi", application = Application::class)
class QueueDragSettleTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun droppedRowSettlesWithoutAJump() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        val queue = mutableStateOf((0..5).map { Song("id$it", "Song $it", "Artist", null) })
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                QueuePane(
                    queue = queue.value, currentIndex = 0, upNext = (1..5).toList(), isPlaying = true, shuffle = false,
                    onPlayIndex = {}, onRemoveIndex = {},
                    // As the player does: the song moves in the queue itself.
                    onMove = { from, to -> queue.value = queue.value.toMutableList().apply { add(to, removeAt(from)) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
        val row = top("Song 3") - top("Song 2")
        val start = top("Song 2")
        compose.mainClock.autoAdvance = false
        // Held, then dragged down a row and a third: it goes one place down and is let go a third of a row past it.
        compose.onNodeWithText("Song 2").performTouchInput {
            down(center)
            repeat(50) { advanceEventTime(16); moveBy(androidx.compose.ui.geometry.Offset(0f, 0f)) }
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, row * 1.33f / 10f)) }
            up()
        }
        val tops = mutableListOf<Float>()
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            tops += top("Song 2")
        }
        compose.runOnIdle { assertEquals(listOf("Song 0", "Song 1", "Song 3", "Song 2", "Song 4", "Song 5"), queue.value.map { it.title }) }
        val home = start + row
        assertEquals(listOf("Song 1", "Song 3", "Song 2", "Song 4", "Song 5"), (1..5).map { "Song $it" }.sortedBy { top(it) })
        assertEquals("it ends in its new place", home, tops.last(), 1.5f)
        assertTrue("it eased in from where it was let go: $tops", tops.first() > home + row * 0.1f)
        val steps = (listOf(start + row * 1.33f) + tops).zipWithNext { a, b -> kotlin.math.abs(a - b) }
        assertTrue("a jump on the way in: $steps (row $row)", steps.max() < row * 0.2f)
    }
}
