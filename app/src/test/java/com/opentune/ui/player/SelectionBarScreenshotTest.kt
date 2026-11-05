package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

/** The lyric-picking bar, with nothing picked and with three lines, as a picture to look at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h300dp-xxhdpi", application = android.app.Application::class)
class SelectionBarScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun bar() {
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Column(Modifier.fillMaxSize().background(Color(0xFF6B4A1E)), verticalArrangement = Arrangement.SpaceEvenly) {
                    SelectionBar(0, {}, {})
                    SelectionBar(3, {}, {})
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/player/selection-bar.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
