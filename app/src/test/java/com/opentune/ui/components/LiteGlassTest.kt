package com.opentune.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.data.settings.liteModeApplied
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In Lite mode, glass with a see-through tint (the mini player's) still hides the page under it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h400dp-xhdpi", application = android.app.Application::class)
class LiteGlassTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun glassIsSolidInLiteMode() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        AppSettings.updateUi { liteModeApplied(it, true) }
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = ThemeMode.DARK, dynamicColor = false)) {
                Box(Modifier.fillMaxSize().background(Color.Red), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(200.dp).glass(RoundedCornerShape(24.dp), Color(0xFF3355AA).copy(alpha = 0.38f)))
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        val middle = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val red = (middle shr 16) and 0xFF
        val green = (middle shr 8) and 0xFF
        // The red page doesn't show through: the glass's face is solid.
        assertTrue("middle pixel ${Integer.toHexString(middle)}", red < green + 40)
    }
}
