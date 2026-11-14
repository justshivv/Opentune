package com.opentune.ui.account

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Discord's sign-in opens on Discord's page, OpenTune's form a tap away, with Discord's logo; drawn to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class DiscordSignInScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun form() {
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                DiscordLoginScreen(onDone = {})
            }
        }
        compose.waitForIdle()
        // The browser fills the page; alternative methods live in Help.
        val page = compose.onNodeWithTag("sign-in-page").fetchSemanticsNode().boundsInRoot
        val browser = compose.onNodeWithTag("sign-in-browser").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(page.height - 56f * compose.activity.resources.displayMetrics.density, browser.height, 1f)
        compose.onNodeWithContentDescription("Sign-in help").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Use OpenTune's form").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("Email or phone number").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sign-in help").assertIsDisplayed()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/account/discord-sign-in.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
