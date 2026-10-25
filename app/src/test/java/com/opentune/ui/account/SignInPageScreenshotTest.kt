package com.opentune.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in page around a service's own page, with a stand-in where the web page goes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class SignInPageScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun show(spotify: Boolean) {
        AppSettings.init(ApplicationProvider.getApplicationContext<android.app.Application>())
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = com.opentune.data.settings.ThemeMode.DARK, dynamicColor = false)) {
                SignInPage(
                    service = if (spotify) "Spotify" else "YouTube Music",
                    brand = if (spotify) SpotifyGreen else YouTubeMusicRed,
                    icon = if (spotify) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle,
                    gives = if (spotify) "Your playlists in Library, each song's Canvas, and suggestions made for you" else "Your playlists, liked songs, history and recommendations",
                    loading = true,
                    onBack = {},
                    hint = if (spotify) "If Google sign-in won't open here, use your Spotify email or username, or a one-time code by email." else null,
                ) { modifier ->
                    Box(modifier.background(Color(0xFFF4F4F4)), contentAlignment = Alignment.Center) { Text("Sign-in page", color = Color.Black) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun spotify() {
        show(spotify = true)
        compose.onNodeWithText("Spotify").assertIsDisplayed()
        save("sign-in-spotify")
    }

    @Test fun youTubeMusic() {
        show(spotify = false)
        compose.onNodeWithText("YouTube Music").assertIsDisplayed()
        save("sign-in-youtube-music")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
