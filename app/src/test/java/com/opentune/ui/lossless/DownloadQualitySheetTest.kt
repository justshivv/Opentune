package com.opentune.ui.lossless

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.lossless.*
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-xhdpi", application = android.app.Application::class)
class DownloadQualitySheetTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    @Before fun init() {
        LosslessDownloads.store = LosslessStore(temp.newFolder())
        ExtensionSessions.init(compose.activity)
    }
    @Test fun narrowPhoneCanChooseQualityAndReachSettingsAndDownloads() {
        var settings = 0; var downloads = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize())
            DownloadQualitySheet(listOf(Song("abcdefghijk", "A long song title for a narrow screen", "An artist", null, "3:10")), {}, { settings++ }, { downloads++ })
        } }
        compose.onNodeWithText("Download quality").assertIsDisplayed()
        compose.onNodeWithText("Low").performScrollTo().performClick()
        compose.onNodeWithText("Max").performScrollTo().performClick()
        compose.onNodeWithText("Download providers in Settings").performScrollTo().performClick()
        compose.onNodeWithText("View downloads").performScrollTo().performClick()
        assertEquals(1, settings); assertEquals(1, downloads)
    }
}
