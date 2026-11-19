package com.opentune.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.BrowseType
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

/** A long press lifts the item out with its menu beside it: the song's rows, or the album's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ContextMenuScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun shot(name: String) {
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        ShadowDialog.getLatestDialog().window!!.decorView.draw(canvas)
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun songRowOpensItsMenuBelow() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                    Spacer(Modifier.height(200.dp))
                    SongListItem(Song("dQw4w9WgXcQ", "Devil", "Sidhu Moosewala", null, "3:10"), onClick = {}, onPlayNext = {}, onAddToQueue = {})
                }
            }
        }
        compose.onNodeWithText("Devil").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Add to playlist").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        shot("context-song")
    }

    @Test fun albumCardNearTheFootOpensItsMenuAbove() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                    Column {
                        Spacer(Modifier.height(560.dp))
                        ItemCard(
                            "Wake Up", "Single • Krish Rao", null, BrowseType.ALBUM, onClick = {},
                            menu = CardMenu.ForList(MenuList("MPREb_x", "Wake Up", "Single • Krish Rao", null, BrowseType.ALBUM)),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Wake Up").performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText("Add to Library").assertIsDisplayed()
        shot("context-album")
    }
}
