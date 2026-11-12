package com.opentune.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.opentune.data.model.Song
import androidx.compose.material.icons.rounded.BugReport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's "…" menu as a page: there on the first frame, no motion, and closed at once. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SongMenuPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val song = Song("abc123def45", "A Song With A Fairly Long Title", "Some Artist", null)

    @Test fun showsAtOnceAndClosesAtOnce() {
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF203040)))
                if (open) SongMenuSheet(song, onDismiss = { open = false }, asPage = true, end = { close ->
                    MenuRow(androidx.compose.material.icons.Icons.Rounded.BugReport, "Copy log") { close() }
                })
            }
        }
        // One frame in, without letting any animation run: everything is already there.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("A Song With A Fairly Long Title").assertExists()
        compose.onNodeWithText("Copy log").assertExists()
        snap("build/screenshots/song-menu-page.png")
        compose.onNodeWithContentDescription("Close").performClick()
        assertTrue(!open)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Copy log").assertDoesNotExist()
    }

    @Test fun pullingTheBarDownClosesIt() {
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                if (open) SongMenuSheet(song, onDismiss = { open = false }, asPage = true)
            }
        }
        compose.waitForIdle()
        // A short pull springs back.
        compose.onNodeWithContentDescription("Close").performTouchInput { swipeDown(centerY, centerY + 60f, 300) }
        compose.waitForIdle()
        assertTrue(open)
        // A long pull takes it away.
        compose.onNodeWithContentDescription("Close").performTouchInput { swipeDown(centerY, centerY + 1_400f, 300) }
        compose.waitForIdle()
        assertTrue(!open)
    }

    private fun snap(path: String) {
        val page = compose.activity.window.decorView
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val image = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        page.draw(canvas)
        dialog.draw(canvas)
        java.io.File(path).apply { parentFile!!.mkdirs() }
            .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
