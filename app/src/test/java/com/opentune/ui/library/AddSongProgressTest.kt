package com.opentune.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentune.data.model.Song
import com.opentune.data.spotify.SpotifyPlaylistWrites.SaveProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AddSongProgressTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun waitingPreventsDuplicateTapFailureCanRetryAndConfirmationDisablesRow() {
        val progress = mutableStateOf(SaveProgress("Saving to Spotify…", busy = true))
        val added = mutableStateOf(false)
        var taps = 0
        compose.setContent {
            MaterialTheme { AddRow(Song("aaaaaaaaaaa", "Song", "Artist", null), added.value, progress.value) { taps++ } }
        }
        compose.onNodeWithText("Saving to Spotify…").assertExists()
        compose.onNodeWithText("Song").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
        compose.runOnIdle { progress.value = SaveProgress("Spotify is busy. Try again shortly.", failed = true) }
        compose.onNodeWithText("Spotify is busy. Try again shortly.").assertExists()
        compose.onNodeWithText("Song").assertIsEnabled().performClick()
        assertEquals(1, taps)
        compose.runOnIdle { added.value = true }
        compose.onNodeWithText("Added").assertExists()
        compose.onNodeWithText("Song").assertIsNotEnabled()
    }
}
