package com.opentune.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
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
import org.robolectric.shadows.ShadowDialog

/** The comments sheet with a few comments, drawn to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class CommentsSheetScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun c(id: String, who: String, text: String, ago: String, likes: String?, replies: String? = null, creator: Boolean = false) =
        SongComments.Comment(id, who, null, text, ago, likes, replies, creator, mine = false)

    @Test fun sheet() {
        val page = SongComments.Page(listOf(
            c("1", "@nightdrive", "This song hits different at 2 am on an empty road.", "2 months ago", "12K", "184"),
            c("2", "@TheWeeknd", "Thank you for listening, all of you.", "1 year ago", "429K", "976", creator = true),
            c("3", "@ana.m", "The synths in the bridge are unreal. Still on repeat in 2026.", "3 weeks ago", "1.6K"),
            c("4", "@kabir", "Who else is here from OpenTune?", "1 day ago", null, "2"),
        ), next = null, createParams = null)
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2A1A12)))
                CommentsSheet(
                    Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", null, "3:20"), onDismiss = {},
                    open = { SongComments.Thread(it, first = "t", turnedOff = false, fromVideo = true) },
                    pageOf = { page },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onNodeWithText("Who else is here from OpenTune?").assertIsDisplayed()
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        File("build/screenshots/player/comments.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
