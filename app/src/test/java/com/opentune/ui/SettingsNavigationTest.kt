package com.opentune.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.ui.settings.SettingsScreen
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var exits = 0

    @Before fun setUp() {
        AppSettings.init(compose.activity)
        AppSettings.updateUi { it.copy(reduceAnimation = true, reduceBlur = true) }
        AppSettings.updateTheme { it.copy(mode = ThemeMode.DARK, colorFromArtwork = false) }
    }

    private fun show(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val theme by AppSettings.theme.collectAsState()
            OpenTuneTheme(theme) {
                Surface {
                    SettingsScreen(PaddingValues(bottom = 24.dp), { exits++ }, {}, {})
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    @Test fun overviewShowsCategoriesAndMotionPageKeepsControlsFocused() {
        show()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("Choose your look").assertDoesNotExist()
        compose.onNodeWithText("Crossfade").assertDoesNotExist()
        save("overview-dark")
        compose.onNodeWithText("Motion & dock").performClick()
        compose.onNodeWithText("Set the pace").assertIsDisplayed()
        compose.onNodeWithText("Hide dock while scrolling").performScrollTo().assertIsDisplayed()
        val before = AppSettings.ui.value.autoHideDock
        compose.onNodeWithText("Hide dock while scrolling").performClick()
        assertEquals(!before, AppSettings.ui.value.autoHideDock)
        compose.onNodeWithText("Player layout").assertDoesNotExist()
        compose.onNodeWithTag("settings:MOTION").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings:overview").assertIsDisplayed()
        assertEquals(0, exits)
    }

    @Test fun globalSearchStillChangesTheRealPreference() {
        show()
        compose.onNodeWithTag("settingsSearch").performTextReplacement("download wifi")
        compose.onNodeWithText("1 setting found").assertIsDisplayed()
        val before = AppSettings.library.value.downloadWifiOnly
        compose.onNodeWithText("Download on Wi-Fi only").performClick()
        assertEquals(!before, AppSettings.library.value.downloadWifiOnly)
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test fun scopedSearchAndHardwareBackStayWithinSettings() {
        show()
        compose.onNodeWithText("Accessibility & comfort").performClick()
        compose.onNodeWithTag("settingsSearch").performTextReplacement("volume")
        compose.onNodeWithText("0 settings found").assertIsDisplayed()
        compose.onNodeWithText("Pause at zero volume").assertDoesNotExist()
        back()
        compose.onNodeWithText("Reduce animation").assertIsDisplayed()
        compose.onNodeWithTag("settings:ACCESSIBILITY").assertIsDisplayed()
        back()
        compose.onNodeWithTag("settings:overview").assertIsDisplayed()
        assertEquals(0, exits)
    }

    @Test fun returningFromAPagePreservesOverviewScroll() {
        show()
        compose.onNodeWithTag("settings:overview").performScrollToNode(hasText("About & updates"))
        compose.onNodeWithText("About & updates").performClick()
        back()
        compose.onNodeWithText("About & updates").assertIsDisplayed()
    }

    @Test fun categoryAndQuerySurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        compose.onNodeWithTag("settings:overview").performScrollToNode(hasText("Lyrics"))
        compose.onNodeWithText("Lyrics").performClick()
        compose.onNodeWithTag("settingsSearch").performTextReplacement("text size")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings:LYRICS").assertIsDisplayed()
        compose.onNodeWithText("Lyrics text size").assertIsDisplayed()
        compose.onNodeWithText("1 setting found").assertIsDisplayed()
    }

    @Test fun lightOverviewAndAppearancePreview() {
        AppSettings.updateTheme { it.copy(mode = ThemeMode.LIGHT) }
        show()
        save("overview-light")
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Choose your look").assertIsDisplayed()
        save("appearance-light")
    }

    @Test
    @Config(qualifiers = "w320dp-h800dp-xhdpi")
    fun compactOverviewKeepsEveryCategoryReachable() {
        show()
        listOf("Appearance", "Motion & dock", "Accessibility & comfort", "Playback & audio", "Lyrics", "Library & downloads", "Accounts & services", "Storage & data", "About & updates").forEach {
            compose.onNodeWithTag("settings:overview").performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("settings:overview").performScrollToIndex(0)
        save("overview-compact")
    }

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun save(name: String) {
        // PixelCopy waits for a hardware frame that Robolectric does not submit.
        // Draw with its native Canvas, as the repository's other preview tests do.
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
        val file = File("build/reports/settings/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
