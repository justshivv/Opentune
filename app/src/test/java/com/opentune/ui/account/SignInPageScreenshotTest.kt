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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertDoesNotExist
import org.junit.Assert.assertEquals
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
        val page = compose.onNodeWithTag("sign-in-page").fetchSemanticsNode().boundsInRoot
        val browser = compose.onNodeWithTag("sign-in-browser").fetchSemanticsNode().boundsInRoot
        val toolbar = 56f * compose.activity.resources.displayMetrics.density
        assertEquals(page.width, browser.width, 1f)
        assertEquals(page.bottom, browser.bottom, 1f)
        assertEquals(page.height - toolbar, browser.height, 1f)
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

    @Test @Config(qualifiers = "w891dp-h411dp-xxhdpi") fun landscapeUsesAllAvailableWidth() {
        show(spotify = false)
    }

    @Test fun helpDoesNotShrinkTheBrowser() {
        show(spotify = true)
        val before = compose.onNodeWithTag("sign-in-browser").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Your playlists in Library, each song's Canvas, and suggestions made for you").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sign-in help").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Your playlists in Library, each song's Canvas, and suggestions made for you").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(before, compose.onNodeWithTag("sign-in-browser").fetchSemanticsNode().boundsInRoot)
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
