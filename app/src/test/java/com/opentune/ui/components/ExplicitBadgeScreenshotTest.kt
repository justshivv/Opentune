package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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

/** The "E" mark shows on explicit rows and cards, and only on those. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h520dp-xxhdpi", application = android.app.Application::class)
class ExplicitBadgeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun marksExplicitOnly() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        compose.setContent {
            MaterialTheme(darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                    SongListItem(Song("a", "HUMBLE.", "Kendrick Lamar", null, "2:57", isExplicit = true), onClick = {})
                    SongListItem(Song("b", "Clean song", "Some Artist", null, "3:10", isExplicit = false), onClick = {})
                    SongListItem(Song("c", "Unknown", "Some Artist", null, "3:10"), onClick = {})
                    Row {
                        ItemCard("DAMN.", "Album • Kendrick Lamar", null, BrowseType.ALBUM, onClick = {}, explicit = true)
                        ItemCard("Clean album", "Album • Someone", null, BrowseType.ALBUM, onClick = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Explicit").assertCountEquals(2)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/explicit.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
