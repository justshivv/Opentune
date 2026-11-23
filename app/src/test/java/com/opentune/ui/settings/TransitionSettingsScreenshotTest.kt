package com.opentune.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.TempoMatch
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Playback › Between songs: the Automix controls, the album guard and the fades on pause and skip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class TransitionSettingsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        AppSettings.updatePlayback { it.copy(automix = true, transportFade = true, transportFadeMs = 1_000, automixSeconds = 0, tempoMatch = TempoMatch.FULL, gaplessAlbums = true) }
        compose.setContent {
            OpenTuneTheme(ThemeSettings().copy(mode = com.opentune.data.settings.ThemeMode.DARK, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(PaddingValues(), onBack = {}, onOpenEqualizer = {}, onOpenReplay = {}, startPage = "playback")
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        AppSettings.updatePlayback { it.copy(automix = false, transportFade = false, automixSeconds = 0, tempoMatch = TempoMatch.FULL, gaplessAlbums = true, automixAlwaysMix = false) }
    }

    private fun list() = compose.onNode(hasScrollToNodeAction())

    @Test fun theControlsShowAndSet() {
        list().performScrollToNode(hasText("Automix length"))
        compose.onNodeWithText("Auto: each blend lasts as long as suits the two songs").assertExists()
        save("settings-automix-controls")
        compose.onNodeWithText("8 s").performClick()
        compose.waitForIdle()
        assertEquals(8, AppSettings.playback.value.automixSeconds)
        compose.onNodeWithText("Each blend lasts about 8 s, kept to whole bars").assertExists()

        list().performScrollToNode(hasText("Tempo match"))
        compose.onNodeWithText("Gentle").performClick()
        compose.waitForIdle()
        assertEquals(TempoMatch.GENTLE, AppSettings.playback.value.tempoMatch)

        list().performScrollToNode(hasText("Keep albums gapless"))
        compose.onNodeWithText("Keep albums gapless").performClick()
        compose.waitForIdle()
        assertFalse(AppSettings.playback.value.gaplessAlbums)

        list().performScrollToNode(hasText("Fade length"))
        compose.onNodeWithText("Each fade lasts 1.0 s").assertExists()
        save("settings-transport-fade")
    }

    @Test fun theAutomixControlsGoWithAutomix() {
        AppSettings.updatePlayback { it.copy(automix = false, crossfadeSeconds = 0, transportFade = false) }
        compose.waitForIdle()
        list().performScrollToNode(hasText("Fade on pause and skip"))
        compose.onNodeWithText("Off: pause, play and skips act at once").assertExists()
        compose.onNodeWithText("Automix length").assertDoesNotExist()
        compose.onNodeWithText("Keep albums gapless").assertDoesNotExist()
        compose.onNodeWithText("Fade length").assertDoesNotExist()
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
