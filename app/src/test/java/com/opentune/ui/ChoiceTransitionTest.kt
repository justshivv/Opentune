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
}
