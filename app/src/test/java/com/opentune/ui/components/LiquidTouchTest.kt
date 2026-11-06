package com.opentune.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.GlassStyle
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Liquid Glass watches a touch without taking it: the control on the glass still gets every tap, and nothing breaks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-xhdpi", application = android.app.Application::class)
class LiquidTouchTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var taps = 0

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { it.copy(liquidGlass = true, glassStyle = GlassStyle.LIQUID, reduceBlur = false, reduceAnimation = false, liteMode = false) }
    }

    private fun show() {
        taps = 0
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                GlassPage(
                    Modifier.fillMaxSize(),
                    overlay = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(200.dp, 80.dp).testTag("glass").glass(RoundedCornerShape(40.dp)).clickable { taps++ })
                        }
                    },
                    content = { Box(Modifier.fillMaxSize().background(Color(0xFF6A3DE8))) },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun aTapOnTheGlassStillReachesWhatIsOnIt() {
        show()
        compose.onNodeWithTag("glass").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test fun holdingAndDraggingAcrossItSettlesWithoutTrouble() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("glass").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(-80f, 10f))
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("glass").performTouchInput { up() }
        // The release spring has plenty of time to settle.
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // A release inside a clickable counts as its click, drag or not; what matters is the next tap still lands.
        val before = taps
        compose.onNodeWithTag("glass").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(before + 1, taps)
    }

    @Test fun withMotionReducedItIsPlainGlassAndStillTaps() {
        AppSettings.updateUi { it.copy(reduceAnimation = true) }
        show()
        compose.onNodeWithTag("glass").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, taps)
    }
}
