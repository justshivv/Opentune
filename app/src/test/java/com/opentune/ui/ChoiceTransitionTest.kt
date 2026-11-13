package com.opentune.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.AudioQuality
import com.opentune.data.settings.ChoiceMenuStyle
import com.opentune.ui.components.ChoiceSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ChoiceTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mobileQualityMovesAsOnePageWithoutResizingOrBouncing() {
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(choiceMenus = ChoiceMenuStyle.SCREEN, reduceAnimation = false) }
        val quality = mutableStateOf(AudioQuality.entries.first())
        val open = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                if (open.value) ChoiceSheet("Quality on mobile data", AudioQuality.entries, quality.value,
                    { it.label }, { quality.value = it }, { open.value = false })
            }
        }
        var left = Float.MAX_VALUE
        var width: Float? = null
        var height: Float? = null
        var rowTop: Float? = null
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val node = compose.onNodeWithTag("choice-screen").fetchSemanticsNode()
            val page = node.boundsInRoot
            val row = compose.onNodeWithText(AudioQuality.entries.first().label).fetchSemanticsNode().boundsInRoot
            width?.let { assertEquals(it, node.size.width.toFloat(), 1f) }
            height?.let { assertEquals(it, node.size.height.toFloat(), 1f) }
            rowTop?.let { assertEquals(it, row.top - page.top, 1f) }
            assertTrue("entrance must not reverse direction", page.left <= left + 1f)
            left = page.left; width = node.size.width.toFloat(); height = node.size.height.toFloat(); rowTop = row.top - page.top
        }
        assertEquals(0, ShadowDialog.getLatestDialog().window!!.attributes.windowAnimations)
        compose.mainClock.autoAdvance = true
        val target = AudioQuality.entries.last()
        compose.onNodeWithText(target.label).performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(target, quality.value)
        assertFalse(open.value)
    }

    /**
     * A choice with an option that can't be had here, one that asks for more before it closes,
     * and a note under the options (the bit-perfect output's), shown as a page of its own.
     */
    @Test fun dimmedOptionsStayAndAKeptOpenChoiceWaits() {
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(choiceMenus = ChoiceMenuStyle.SCREEN, reduceAnimation = true) }
        val picked = mutableStateOf("Off")
        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) ChoiceSheet(
                    "Bit-perfect output", listOf("Off", "USB DAC", "Direct"), picked.value,
                    { if (it == "USB DAC") "$it · Needs Android 14 or newer" else it },
                    { picked.value = it }, { open.value = false },
                    enabled = { it != "USB DAC" },
                    keepOpen = { it == "Direct" },
                    footer = { androidx.compose.material3.Text("Sends the track to the DAC unchanged.") },
                )
            }
        }
        compose.waitForIdle()
        // Shown as a page of its own, as Choice menus asks, with the note under the options.
        compose.onNodeWithTag("choice-screen").assertExists()
        compose.onNodeWithText("Sends the track to the DAC unchanged.").assertExists()
        // The dimmed option doesn't respond.
        compose.onNodeWithText("USB DAC").performClick()
        compose.waitForIdle()
        assertEquals("Off", picked.value)
        assertTrue(open.value)
        // One that asks for more is chosen and leaves the page open.
        compose.onNodeWithText("Direct").performClick()
        compose.waitForIdle()
        assertEquals("Direct", picked.value)
        assertTrue(open.value)
        // An ordinary one is chosen and closes it.
        compose.onNodeWithText("Off").performClick()
        compose.waitForIdle()
        assertEquals("Off", picked.value)
        assertFalse(open.value)
    }
}
