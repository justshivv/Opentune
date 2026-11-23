package com.opentune.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Turned on its side in video mode, the player shows the music video and keeps the Song | Video switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = Application::class)
class LandscapeVideoTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
    }

    @Test fun sidewaysVideoModeShowsThePictureAndTheSwitch() {
        val song = Song("x", "Track", "Artist", null, "3:00")
        var asked: Boolean? = null
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false,
            repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000, lyrics = LyricsState.NotFound, queue = listOf(song),
            currentIndex = 0, upNext = emptyList(), sound = SoundSettings(), theme = ThemeSettings(),
            ui = InterfaceSettings(reduceAnimation = true), videoMode = true, videoAvailable = true,
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(
                    state, position = { 29_000 }, buffered = { 60_000 },
                    actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, setVideoMode = { asked = it }),
                    videoSurface = { Box(Modifier.matchParentSize().testTag("video-picture")) },
                )
            }
        }
        compose.onNodeWithTag("video-picture").assertIsDisplayed()
        compose.onNodeWithText("Song").performClick()
        compose.runOnIdle { assertEquals(false, asked) }
    }
}
