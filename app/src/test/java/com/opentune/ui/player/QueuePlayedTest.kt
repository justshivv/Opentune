package com.opentune.ui.player

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The queue keeps the songs already played, folded under one row, to open and play again (#50). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QueuePlayedTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun playedSongsFoldAwayAndPlayAgain() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        val queue = (0..4).map { Song("id$it", "Song $it", "Artist", null) }
        var played = -1
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                QueuePane(
                    queue = queue, currentIndex = 2, upNext = listOf(3, 4), isPlaying = true, shuffle = false,
                    onPlayIndex = { played = it }, onRemoveIndex = {}, onMove = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Song 0").assertDoesNotExist()
        compose.onNodeWithText("Played · 2").performClick()
        compose.onNodeWithText("Song 1").performClick()
        compose.runOnIdle { assertEquals(1, played) }
        compose.onNodeWithText("Song 0").assertExists()
        compose.onNodeWithText("Song 2").assertExists()
    }
}
