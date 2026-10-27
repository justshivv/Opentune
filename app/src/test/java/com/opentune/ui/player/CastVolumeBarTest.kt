package com.opentune.ui.player

import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.opentune.cast.CastMedia
import com.opentune.cast.Receiver
import com.opentune.cast.RemoteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CastVolumeBarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun controlsReceiverAndKeepsFollowingItsReportsWithoutChangingPhoneVolume() {
        val receiver = TestReceiver()
        val audio = compose.activity.getSystemService(AudioManager::class.java)
        val phoneVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        compose.setContent { MaterialTheme { OutputVolumeBar(receiver, Modifier) } }
        val bar = compose.onNodeWithContentDescription("Living room volume")
        bar.assertRangeInfoEquals(ProgressBarRangeInfo(0.4f, 0f..1f))
        bar.performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        compose.runOnIdle { assertEquals(0.7f, receiver.status.value.volume!!, 0.001f) }
        compose.onNodeWithContentDescription("Volume down").performClick()
        compose.runOnIdle { assertEquals(0.65f, receiver.status.value.volume!!, 0.001f) }
        compose.onNodeWithContentDescription("Volume up").performClick()
        compose.runOnIdle { assertEquals(0.7f, receiver.status.value.volume!!, 0.001f) }
        // Changing volume with the TV remote must update a slider already used here.
        compose.runOnIdle { receiver.status.value = receiver.status.value.copy(volume = 0.25f) }
        bar.assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        compose.runOnIdle { assertEquals(phoneVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    }

    private class TestReceiver : Receiver {
        override val name = "Living room"
        override val status = MutableStateFlow(RemoteStatus(volume = 0.4f))
        override suspend fun setVolume(level: Float) { status.value = status.value.copy(volume = level) }
        override suspend fun load(media: CastMedia, positionMs: Long, play: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun seek(positionMs: Long) = Unit
        override fun close() = Unit
    }
}
