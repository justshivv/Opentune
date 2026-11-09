package com.opentune.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.opentune.data.comments.SongComments
import com.opentune.data.model.Song
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        compose.onNodeWithText("This song hits different at 2 am on an empty road.").assertIsDisplayed()
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        File("build/screenshots/player/comments.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Loading must not freeze the sheet at its loading height; the handle can expand it. */
    @Test fun expandsAndCloses() {
        val loaded = kotlinx.coroutines.CompletableDeferred<SongComments.Page>()
        val many = (1..30).map { c("$it", "@listener$it", "Comment number $it, long enough to wrap onto a second line now and then.", "$it days ago", "${it * 3}") }
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF2A1A12)))
                if (open) CommentsSheet(
                    Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", null, "3:20"), onDismiss = { open = false },
                    open = { SongComments.Thread(it, first = "t", turnedOff = false, fromVideo = false) },
                    pageOf = { loaded.await() },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val screen = compose.activity.window.decorView.height.toFloat()
        val half = compose.onNodeWithText("Comments").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("opens halfway: $half of $screen", half > screen * 0.35f)
        compose.runOnIdle { loaded.complete(SongComments.Page(many, null, null)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Sheet drag handle").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, -700f), 300)
        }
        compose.mainClock.advanceTimeBy(1000)
        val top = compose.onNodeWithText("Comments").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue("expanded title at $top of $screen", top < screen * 0.2f)
        // The handle also supports click/remote/assistive expansion and collapse.
        compose.onNodeWithContentDescription("Sheet drag handle").performClick()
        compose.mainClock.advanceTimeBy(1000)
        org.junit.Assert.assertTrue(compose.onNodeWithText("Comments").fetchSemanticsNode().boundsInRoot.top > screen * 0.35f)
        // Down: the list to its top, the card back to half, then away.
        repeat(6) {
            if (!open) return@repeat
            compose.onNodeWithText("Comments").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 700f), 250) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        org.junit.Assert.assertTrue("closed", !open)
    }

    /** Pulled down by the list itself: back to half once it's at its top, then away. */
    @Test fun pullingTheListDownCloses() {
        val many = (1..30).map { c("$it", "@listener$it", "Comment number $it.", "$it days ago", null) }
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                if (open) CommentsSheet(
                    Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", null, "3:20"), onDismiss = { open = false },
                    open = { SongComments.Thread(it, first = "t", turnedOff = false, fromVideo = false) },
                    pageOf = { SongComments.Page(many, null, null) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        repeat(6) {
            if (!open) return@repeat
            compose.onNodeWithText("Comment number 1.").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 600f), 200) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        org.junit.Assert.assertTrue("closed", !open)
    }
}
