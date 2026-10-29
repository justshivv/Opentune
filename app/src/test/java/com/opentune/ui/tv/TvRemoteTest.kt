package com.opentune.ui.tv

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.player.seekStepMs
import com.opentune.ui.settings.SettingsScreen
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app on a TV: found as one, and driven by the remote's arrows and OK button. */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = android.app.Application::class)
class TvRemoteTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var inputModes: androidx.compose.ui.input.InputModeManager

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        compose.setContent {
            inputModes = androidx.compose.ui.platform.LocalInputModeManager.current
            OpenTuneTheme(ThemeSettings().copy(mode = com.opentune.data.settings.ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(PaddingValues(), onBack = {}, onOpenEqualizer = {}, onOpenReplay = {})
                }
            }
        }
        // A TV is never in touch mode, where rows can't take focus.
        compose.runOnIdle { inputModes.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        compose.waitForIdle()
    }

    @Test fun aTelevisionIsRecognised() {
        assertTrue(Tv.isTv(compose.activity))
    }

    @Test fun theArrowsMoveFocusAndOkOpensWhatIsFocused() {
        // Nothing is focused until the first press, as on a TV.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNode(isFocused()).assertExists()

        // From the first row, down until Appearance is focused, then OK opens it.
        compose.onNode(hasText("Accounts") and isFocusable()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        save("tv-settings-focused")
        var presses = 0
        while (compose.onAllNodes(isFocused() and hasText("Appearance")).fetchSemanticsNodes().isEmpty()) {
            assertTrue("The remote never reached Appearance", ++presses < 15)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Theme"))
        compose.onNodeWithText("Theme").assertIsDisplayed()
        save("tv-settings-look")
    }

    @Test fun leftAndRightOnTheSeekBarMoveTenSecondsOrAFiftieth() {
        assertEquals(10_000L, seekStepMs(Key.DirectionRight, 200_000))
        assertEquals(-10_000L, seekStepMs(Key.DirectionLeft, 200_000))
        assertEquals(60_000L, seekStepMs(Key.DirectionRight, 3_000_000))
        assertNull(seekStepMs(Key.DirectionUp, 200_000))
        assertNull(seekStepMs(Key.DirectionRight, 0))
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
