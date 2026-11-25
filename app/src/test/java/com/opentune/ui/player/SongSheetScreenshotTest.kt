package com.opentune.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.PageLayout
import com.opentune.data.settings.ThemeMode
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.components.Artwork
import com.opentune.ui.theme.OpenTuneTheme
import com.opentune.ui.theme.Text
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The song sheet and About song stacked on it, over a stand-in for the player, drawn to
 * build/screenshots/player/songinfo. Every song, artist and text in them is invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SongSheetScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.CLASSIC, reduceBlur = true) }
    }

    @After fun reset() {
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.CLASSIC) }
    }

    private fun show(mode: ThemeMode, song: Song = SongSheetFixtures.song, info: SongInfo = SongSheetFixtures.full, full: Boolean = true) {
        SongSheetFixtures.installImages(compose.activity)
        compose.setContent {
            OpenTuneTheme(ThemeSettings(mode = mode, dynamicColor = false)) {
                PlayerStandIn(song, dark = mode == ThemeMode.DARK)
                SongInfoSheets(
                    song, info,
                    SongSheetActions(
                        openArtist = {},
                        openAlbum = if (full) ({}) else null,
                        streamInfo = {},
                        allCredits = if (full) ({}) else null,
                    ),
                    onDismiss = {},
                    durationMs = 221_000,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun stackAbout() {
        compose.onNodeWithText("About song").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val back = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        back.draw(canvas)
        val sheet = ShadowDialog.getLatestDialog().window!!.decorView
        canvas.translate(0f, (back.height - sheet.height).toFloat())
        sheet.draw(canvas)
        File("build/screenshots/player/songinfo/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun songSheetDark() {
        show(ThemeMode.DARK)
        compose.onNodeWithText("View Nova Reyes").assertIsDisplayed()
        shoot("song-sheet-dark")
    }

    @Test fun songSheetLight() {
        show(ThemeMode.LIGHT)
        shoot("song-sheet-light")
    }

    @Test fun aboutDark() {
        show(ThemeMode.DARK)
        stackAbout()
        compose.onNodeWithText("General information").assertIsDisplayed()
        shoot("about-dark-top")
        // To the end of the page, by the finger, as it would be read.
        repeat(3) {
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("General information")))
                .performTouchInput { swipeUp(startY = bottom - 40f, endY = top + 40f, durationMillis = 400) }
            compose.mainClock.advanceTimeBy(1_500)
        }
        compose.onNodeWithText("Spotify").assertIsDisplayed()
        shoot("about-dark-scrolled")
    }

    @Test fun aboutLight() {
        show(ThemeMode.LIGHT)
        stackAbout()
        shoot("about-light-top")
        compose.onNodeWithText("By").performScrollTo()
        compose.mainClock.advanceTimeBy(600)
        shoot("about-light-scrolled")
    }

    @Test fun poster() {
        AppSettings.updateUi { it.copy(pageLayout = PageLayout.POSTER) }
        show(ThemeMode.DARK)
        shoot("poster-song-sheet")
        stackAbout()
        shoot("poster-about")
    }

    /** Nothing found for the song: no description, no write-up, no credits, no catalogue. */
    @Test fun littleData() {
        show(ThemeMode.DARK, SongSheetFixtures.bare, SongInfo(), full = false)
        compose.onNodeWithText("From YouTube").assertDoesNotExist()
        shoot("little-song-sheet")
        stackAbout()
        compose.onNodeWithText("Credits").assertDoesNotExist()
        compose.onNodeWithText("About").assertDoesNotExist()
        compose.onNodeWithText("Read it on YouTube").assertDoesNotExist()
        shoot("little-about")
    }
}

/** Something like the full player behind the sheets: the cover's colour, the cover, the title. */
@androidx.compose.runtime.Composable
private fun PlayerStandIn(song: Song, dark: Boolean) {
    val top = if (dark) Color(0xFF5A2B28) else Color(0xFFF3D9CF)
    val bottom = if (dark) Color(0xFF1A1020) else Color(0xFFE9E0EE)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 28.dp, vertical = 64.dp)) {
            Artwork(song.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(28.dp))
            Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp),
                color = if (dark) Color.White else Color.Black)
            Text(song.artist, style = MaterialTheme.typography.titleMedium, color = if (dark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f))
        }
    }
}
