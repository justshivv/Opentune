package com.opentune.ui.player

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.components.PlayerMorph
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #57: Settings › Keep the screen on for lyrics holds the screen on while the lyrics
 * are the page in view, and only then. The lyrics stay built behind the cover, so the view
 * held the flag itself for as long as the player was open, on the cover too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class LyricsKeepScreenOnTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        PlayerMorph.playerCover = null
        PlayerMorph.lastPlayerCover = null
        PlayerMorph.flying = false
    }

    private fun held(v: View): Boolean = v.keepScreenOn || (v is ViewGroup && (0 until v.childCount).any { held(v.getChildAt(it)) })

    private fun screenHeld() = held(compose.activity.window.decorView)

    @Test fun onlyTheLyricsPageKeepsTheScreenOn() {
        val song = Song("test", "Test track", "Test artist", null, "3:00")
        val lyrics = Lyrics.Synced(listOf(LyricLine(0, 19_000, "First line", emptyList(), false), LyricLine(20_000, 30_000, "Second line", emptyList(), false)), "test")
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true,
            shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, durationMs = 180_000,
            lyrics = LyricsState.Found(lyrics), queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), source = null,
            ui = InterfaceSettings(keepScreenOnLyrics = true, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                PlayerLayout(state, { 5_000L }, { 0L }, PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}))
            }
        }
        // Long enough for the lyrics to be built out of sight behind the cover.
        compose.mainClock.advanceTimeBy(2_000)
        assertFalse("the cover page must not hold the screen on", screenHeld())
        compose.onNodeWithContentDescription("Lyrics").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("the lyrics page holds the screen on", screenHeld())
        compose.onNodeWithContentDescription("Lyrics").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertFalse("back on the cover, the screen may sleep again", screenHeld())
    }
}
