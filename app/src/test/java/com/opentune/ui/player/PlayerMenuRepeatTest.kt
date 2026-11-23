package com.opentune.ui.player

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's "…" opens its menu, and repeat can be changed there without opening the queue (#46). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PlayerMenuRepeatTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
    }

    @Test fun repeatFromTheMenu() = repeatFromTheMenu(lite = false)

    /** Settings › Lite three-dot menu: the menu as a plain page, the way the player's opened in 1.1.0. */
    @Test fun repeatFromTheLiteMenu() = repeatFromTheMenu(lite = true)

    private fun repeatFromTheMenu(lite: Boolean) {
        AppSettings.updateUi { it.copy(liteThreeDotMenu = lite) }
        val song = Song("test", "Test track", "Test artist", null, "3:00")
        var cycles = 0
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.NotFound, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 5_000L }, { 0L }, PlayerActions({}, {}, {}, {}, cycleRepeat = { cycles++ }, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Repeat all").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, cycles) }
        AppSettings.updateUi { it.copy(liteThreeDotMenu = false) }
    }
}
