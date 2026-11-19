package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PageLayout
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The player's title in the Poster layout, drawn to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PosterTitleScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun posterTitle() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.POSTER, reduceBlur = true) }
        compose.setContent {
            MaterialTheme(darkColorScheme(surface = Color(0xFF2D3540), onSurface = Color.White, onSurfaceVariant = Color(0xFFB4BCC6))) {
                androidx.compose.material3.Surface(color = Color(0xFF2D3540), contentColor = Color.White) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2D3540))) {
                        TitleFlight(Song("x", "Run For Roses", "NMIXX", null), false,
                            bigTitle = Rect(72f, 400f, 1160f, 640f), big = Rect(0f, 0f, 1233f, 360f),
                            small = Rect(0f, 0f, 64f, 64f), q = { 0f }, origin = Offset.Zero, onLike = {}, onMore = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height / 3, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "poster-player-title.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
