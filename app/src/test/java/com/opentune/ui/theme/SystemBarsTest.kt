package com.opentune.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("DEPRECATION")
    @Test fun openingDarkPlayerFromLightHomeUpdatesBothBarsAndClosingRestoresLightIconsPolicy() {
        val background = mutableStateOf(Color.White)
        compose.setContent { SystemBars(background.value) }
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            background.value = Color.Black
        }
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            assertFalse(window.isNavigationBarContrastEnforced)
            assertEquals(AndroidColor.TRANSPARENT, window.navigationBarColor)
            background.value = Color.White
        }
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
    }
}
