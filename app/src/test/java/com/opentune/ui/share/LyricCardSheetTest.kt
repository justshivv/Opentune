package com.opentune.ui.share

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** The share sheet opens on the line being sung, and the other lines can be tapped onto the card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LyricCardSheetTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun picksLines() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        val lines = listOf(
            "Oh Danny boy, the pipes, the pipes are calling",
            "From glen to glen, and down the mountain side",
            "The summer's gone, and all the roses falling",
            "'Tis you, 'tis you must go and I must bide",
            "But come ye back when summer's in the meadow",
            "Or when the valley's hushed and white with snow",
        )
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
                LyricCardSheet(Song("x", "Danny Boy", "Johnny Cash", null), lines, startAt = 1, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNodeWithText("1 of 6 lines picked").assertIsDisplayed()
        compose.onNodeWithText(lines[2]).assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("2 of 6 lines picked").assertIsDisplayed()

        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        File("build/screenshots/share/lyrics-sheet.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * As a video, the pills, the preview, the button and both switches are more than a phone's
     * card holds: they scroll, and the last switch comes clear of Save and Share, not under them.
     */
    @Test fun videoControlsScrollClearOfTheButtons() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        val timed = (0 until 4).map { i ->
            com.opentune.data.lyrics.LyricLine(10_000L + i * 3_000, 13_000L + i * 3_000, "Kurta ae kaala beeba line $i", emptyList(), false)
        }
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
                LyricCardSheet(Song("x", "At Peace", "Karan Aujla", null), timed.map { it.text }, startAt = 0, onDismiss = {}, pickable = false, timed = timed)
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val toggle = compose.onNodeWithText("Include the song").performScrollTo().fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("the switch ($toggle) runs under Save ($save)", toggle.bottom <= save.top + 1f)
    }
}
