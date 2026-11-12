package com.opentune.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1100dp-xxhdpi", application = android.app.Application::class)
class LinkDeviceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        // Saved for a look only when the folder has been made by hand.
        val dir = File("build/link-shots").takeIf { it.isDirectory } ?: return
        val v = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun eachDeviceShowsItsSteps() {
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface { LinkDeviceScreen(PaddingValues(), onBack = { }, onSignIn = { }, onTogether = { }) }
            }
        }
        compose.onNodeWithText("Install WEAV on the TV").assertExists()
        shot("tv")
        compose.onNodeWithText("Computer").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Open the web player").assertExists()
        compose.onNodeWithText("Install WEAV on the TV").assertDoesNotExist()
        shot("computer")
        compose.onNodeWithText("Speaker").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Find devices").assertExists()
        shot("speaker")
        compose.onNodeWithText("Friend").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Listen together").assertExists()
        compose.onNodeWithText("Watch").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Build the watch app").assertExists()
    }
}
