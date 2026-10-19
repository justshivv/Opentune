package com.opentune.ui.lossless

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.opentune.data.lossless.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h740dp-xhdpi", application = android.app.Application::class)
class LosslessCardTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val entry = LosslessEntry(LosslessTrack("B012345678", "A long song title that wraps neatly", "An artist with a long name"), "test")
    @Test fun meteredQueueShowsReasonAndWorkingMobileOverrideAndDetails() {
        var mobile = 0; var details = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize())
            LosslessCard(entry, waitingReason = "Waiting for Wi-Fi or another unmetered network", showMobileAction = true,
                onMobile = { mobile++ }, onDetails = { details++ })
        } }
        compose.onNodeWithText("Waiting for Wi-Fi or another unmetered network").assertIsDisplayed()
        compose.onNodeWithText("Use mobile data").performClick(); compose.onNodeWithText("Details").performClick()
        assertEquals(1, mobile); assertEquals(1, details)
    }
    @Test fun providerFailureShowsRetryWithoutClaimingPlayableAudio() {
        var retry = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize())
            LosslessCard(entry.copy(state = LosslessState.FAILED, error = "Requesting audio: HTTP 403"), onRetry = { retry++ })
        } }
        compose.onNodeWithText("Requesting audio: HTTP 403").assertIsDisplayed()
        compose.onNodeWithText("Play").assertDoesNotExist(); compose.onNodeWithText("Retry").performClick(); assertEquals(1, retry)
    }
    @Test fun measuredQualityAndOfflineActionsRemainAccessibleOnNarrowPhone() {
        var play = 0; var export = 0; var remove = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize())
            LosslessCard(entry.copy(state = LosslessState.DONE, bits = 24, sampleRate = 96000, bytes = 25000000),
                onPlay = { play++ }, onExport = { export++ }, onRemove = { remove++ })
        } }
        compose.onNodeWithText("FLAC · 24-bit / 96.0 kHz", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Play").performClick(); compose.onNodeWithText("Export").performClick(); compose.onNodeWithText("Remove").performClick()
        assertEquals(1, play); assertEquals(1, export); assertEquals(1, remove)
    }
    @Test fun verificationWaitOffersRecoveryAndCancellation() {
        var verify = 0; var cancel = 0
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize())
            LosslessCard(entry.copy(state = LosslessState.WAITING), onConnect = { verify++ }, onCancel = { cancel++ })
        } }
        compose.onNodeWithText("Verify provider").performClick(); compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Play").assertDoesNotExist(); assertEquals(1, verify); assertEquals(1, cancel)
    }
}
