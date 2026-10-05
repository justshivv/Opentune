package com.opentune.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the dock to build/screenshots so its look can be checked. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class DockScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val tabs = listOf(
        ChromeTab("Home", Icons.Outlined.Home, Icons.Rounded.Home),
        ChromeTab("Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
        ChromeTab("Podcasts", Icons.Outlined.Podcasts, Icons.Rounded.Podcasts),
        ChromeTab("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    )

    @Test fun dock() {
        compose.setContent {
            MaterialTheme(darkColorScheme(primary = Color(0xFFFF8A65))) { androidx.compose.material3.Surface(color = Color(0xFF0B0B0E)) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF3A2A4A), Color(0xFF101014)))).padding(vertical = 24.dp)) {
                    BottomChrome(
                        inline = false,
                        tabs = tabs,
                        selected = 1,
                        onSelect = {},
                        onExpand = {},
                        searchSelected = false,
                        onSearch = {},
                        mini = null,
                    )
                }
            }
            }
        }
        save("dock")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
