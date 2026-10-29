package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.settings.SettingsScreen
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings: the main page of categories, one category's page, and search across them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SettingsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = com.opentune.data.settings.ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(PaddingValues(), onBack = {}, onOpenEqualizer = {}, onOpenReplay = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun categoriesThenAPage() {
        save("settings-home")
        compose.onNodeWithText("Appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("COLOUR", ignoreCase = true))
        compose.onNodeWithText("COLOUR", ignoreCase = true).assertExists()
        save("settings-look")
    }

    @Test fun searchFindsAcrossPages() {
        compose.onNodeWithText("Search settings").performTextInput("jelly")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Dock lens").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Motion and feel · Dock", ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        // Each setting lives on one page only.
        assertTrue(compose.onAllNodesWithText("Dock lens").fetchSemanticsNodes().size == 1)
        save("settings-search")
    }

    @Test fun seekBarSettingsFoldUntilSeeMore() {
        list().performScrollToNode(hasText("Player"))
        compose.onNodeWithText("Player").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        list().performScrollToNode(hasText("Wavy seek bar"))
        compose.onNodeWithText("Wavy seek bar").assertIsDisplayed()
        compose.onNodeWithText("Seek bar thickness").assertDoesNotExist()
        list().performScrollToNode(hasText("See 5 more"))
        compose.onNodeWithText("See 5 more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Seek bar thickness").assertExists()
        list().performScrollToNode(hasText("See less"))
        compose.onNodeWithText("See less").assertIsDisplayed()
        save("settings-seek-bar")
    }

    @Test fun backFromAPageKeepsTheListWhereItWas() {
        list().performScrollToNode(hasText("About"))
        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("About").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // Still scrolled down: the last category is in view, not the page title.
        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("Search settings").assertIsNotDisplayed()
    }

    private fun list() = compose.onNode(hasScrollToNodeAction())

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
