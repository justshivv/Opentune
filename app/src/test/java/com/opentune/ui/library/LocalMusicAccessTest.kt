package com.opentune.ui.library

import android.Manifest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Access to audio files given in the app's settings while away: coming back, the page reads the
 * phone's songs instead of still asking for access it already has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LocalMusicAccessTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        AppSettings.init(ApplicationProvider.getApplicationContext<android.app.Application>())
    }

    @Test fun accessGivenWhileAwayIsSeenOnComingBack() {
        compose.setContent {
            LocalMusicScreen(PaddingValues(), SongActions(null, false, { _, _, _, _ -> }, {}, {}), onBack = {})
        }
        compose.onNodeWithText("Allow access").assertExists()

        // Off to the app's settings, access given there, and back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        compose.onNodeWithText("Allow access").assertDoesNotExist()
    }
}
