package com.opentune.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.ui.ScreenCache
import com.opentune.ui.browse.SongActions
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class HomePicksTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun picksLeadHomeAndHistoryIsOneTapAway() {
        val picks = HomeShelf("Recommended for you", (1..8).map { ShelfItem("Song $it", "Artist", null, "id$it", null) })
        ScreenCache.put("home:${com.opentune.data.settings.AppSettings.playback.value.recommender.name}", listOf(picks))
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Surface {
                    HomeScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Quick picks").assertExists()
        compose.onNodeWithText("Song 1").assertExists()
        compose.waitForIdle()
        val heading = compose.onNodeWithText("Quick picks").fetchSemanticsNode().boundsInRoot
        val history = compose.onNodeWithContentDescription("Recently played").fetchSemanticsNode().boundsInRoot
        val recognize = compose.onNodeWithContentDescription("Name a song playing nearby").fetchSemanticsNode().boundsInRoot
        val account = compose.onNodeWithContentDescription("Account and settings").fetchSemanticsNode().boundsInRoot
        assertTrue("History must align with Quick picks", kotlin.math.abs(heading.center.y - history.center.y) < 2f)
        assertTrue("Top actions must be separated", recognize.right < account.left)
        assertTrue("History must be below the top actions", history.top > recognize.bottom)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/home/quick-picks.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("Recently played").performClick()
        compose.onNodeWithText("Your recently played songs will appear here").assertExists()
    }
}
