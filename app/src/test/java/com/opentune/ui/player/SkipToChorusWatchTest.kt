package com.opentune.ui.player

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.SongPart
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Skip to chorus" button's next chorus is looked for only while the button is on: with
 * it off the player read the position twice a second for nothing, the whole time it was open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SkipToChorusWatchTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
    }

    @Test fun nothingIsWatchedWithTheButtonOff() {
        assertEquals(ChorusWatch.OFF, chorusWatch(buttonOn = false, hasChoruses = true, controlsHidden = false, playing = true))
        assertEquals(ChorusWatch.OFF, chorusWatch(buttonOn = false, hasChoruses = true, controlsHidden = true, playing = false))
        assertEquals(ChorusWatch.OFF, chorusWatch(buttonOn = true, hasChoruses = false, controlsHidden = false, playing = true))
    }

    @Test fun pollsOnlyWhilePlayingInSight() {
        assertEquals(ChorusWatch.POLL, chorusWatch(buttonOn = true, hasChoruses = true, controlsHidden = false, playing = true))
        assertEquals(ChorusWatch.HOLD, chorusWatch(buttonOn = true, hasChoruses = true, controlsHidden = true, playing = true))
        assertEquals(ChorusWatch.ON_MOVE, chorusWatch(buttonOn = true, hasChoruses = true, controlsHidden = false, playing = false))
    }

    private fun showButton(on: Boolean, playing: Boolean): Boolean {
        val song = Song("test", "Test track", "Test artist", null, "3:00")
        val lyrics = Lyrics.Synced(
            listOf(LyricLine(0, 19_000, "First line", emptyList(), false), LyricLine(60_000, 70_000, "Chorus line", emptyList(), false)),
            "test",
            parts = listOf(SongPart("Verse", 0, 59_000), SongPart("Chorus", 60_000, 80_000), SongPart("Chorus", 120_000, 140_000)),
        )
        val state = PlayerUiState(
            song = song, isPlaying = playing, isSounding = playing, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.Found(lyrics), queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(skipToChorus = on, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 5_000L }, { 0L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        return compose.onAllNodesWithText("Skip to chorus").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theButtonStillFindsTheChorusWhilePaused() {
        assertTrue(showButton(on = true, playing = false))
    }

    @Test fun theButtonFindsTheChorusWhilePlaying() {
        assertTrue(showButton(on = true, playing = true))
    }

    @Test fun noButtonWhenItsOff() {
        assertFalse(showButton(on = false, playing = true))
    }
}
