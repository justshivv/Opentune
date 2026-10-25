package com.opentune.data.history

import androidx.test.core.app.ApplicationProvider
import com.opentune.data.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryPrivacyTest {
    @Test fun pausedCollectionsDoNotEnterSpeedDialOrReappearOnResume() {
        AppSettings.init(ApplicationProvider.getApplicationContext())
        val settings = AppSettings.playback.value
        ListenedCollections.clear()
        try {
            AppSettings.updatePlayback { it.copy(pauseListeningHistory = true) }
            ListenedCollections.played("private", "Private album", "Artist", null, now = 1)
            assertTrue(ListenedCollections.plays.value.isEmpty())
            AppSettings.updatePlayback { it.copy(pauseListeningHistory = false) }
            ListenedCollections.played("visible", "Visible album", "Artist", null, now = 2)
            assertEquals(listOf("visible"), ListenedCollections.plays.value.map { it.browseId })
        } finally {
            ListenedCollections.clear()
            AppSettings.updatePlayback { settings }
        }
    }
}
