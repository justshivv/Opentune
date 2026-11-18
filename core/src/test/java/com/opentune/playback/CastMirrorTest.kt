package com.opentune.playback

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.opentune.cast.CastMedia
import com.opentune.cast.Receiver
import com.opentune.cast.RemoteState
import com.opentune.cast.RemoteStatus
import com.opentune.playback.dsp.DspAudioProcessor
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CastMirrorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() {
        scope.cancel()
        DspAudioProcessor.silenced = false
    }

    /** A phone player that only keeps whether it's playing, and whether it was audible when it last played. */
    private class FakePlayer {
        var playWhenReady = false
        var audibleWhilePlaying = false
        val listeners = mutableListOf<Player.Listener>()

        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "play" -> { setPlaying(true); null }
                "pause" -> { setPlaying(false); null }
                "setPlayWhenReady" -> { setPlaying(args[0] as Boolean); null }
                "getPlayWhenReady" -> playWhenReady
                "addListener" -> { listeners += args[0] as Player.Listener; null }
                "removeListener" -> { listeners -= args[0] as Player.Listener; null }
                "getCurrentMediaItem" -> null
                "getCurrentPosition" -> 0L
                "hasNextMediaItem" -> false
                "getNextMediaItemIndex" -> androidx.media3.common.C.INDEX_UNSET
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args[0] === this
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player

        private fun setPlaying(on: Boolean) {
            if (on && !DspAudioProcessor.silenced) audibleWhilePlaying = true
            if (playWhenReady == on) return
            playWhenReady = on
            listeners.toList().forEach { it.onPlayWhenReadyChanged(on, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        }
    }

    private class FakeReceiver : Receiver {
        override val name = "Spare speaker"
        val state = MutableStateFlow(RemoteStatus(RemoteState.PLAYING, 30_000, 0))
        override val status: StateFlow<RemoteStatus> = state
        override suspend fun load(media: CastMedia, positionMs: Long, play: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun seek(positionMs: Long) = Unit
        override suspend fun setVolume(level: Float) = Unit
        override fun close() = Unit
    }

    private fun mirror(phone: FakePlayer) = CastMirror(ApplicationProvider.getApplicationContext(), phone.player, scope)

    @Test fun silencesThePhoneWhileCasting() {
        val phone = FakePlayer().apply { playWhenReady = true }
        val mirror = mirror(phone)
        mirror.attach(FakeReceiver())
        assertTrue(DspAudioProcessor.silenced)
        assertTrue(phone.playWhenReady)
    }

    /**
     * The device dropping the connection on its own (its socket closing, as in
     * "Spare speaker went away") doesn't mean it stopped playing: what it was
     * given goes on from its buffer. The phone mustn't take over out loud.
     */
    @Test fun aDeviceThatGoesAwayLeavesThePhonePausedNotPlayingAloud() {
        val phone = FakePlayer().apply { playWhenReady = true }
        val mirror = mirror(phone)
        val device = FakeReceiver()
        mirror.attach(device)

        device.state.value = device.state.value.copy(state = RemoteState.GONE)

        assertFalse("the phone went on playing after the device went away", phone.playWhenReady)
        assertFalse(phone.audibleWhilePlaying)
        assertFalse(mirror.active)
        assertFalse(DspAudioProcessor.silenced)
    }

    /** The same while the phone was waiting on the device to buffer: it isn't started either. */
    @Test fun aDeviceThatGoesAwayWhileBufferingDoesNotStartThePhone() {
        val phone = FakePlayer().apply { playWhenReady = true }
        val mirror = mirror(phone)
        val device = FakeReceiver()
        mirror.attach(device)
        device.state.value = RemoteStatus(RemoteState.BUFFERING, 0, 0)
        assertFalse(phone.playWhenReady)

        device.state.value = RemoteStatus(RemoteState.GONE, 0, 0)

        assertFalse(phone.playWhenReady)
        assertFalse(phone.audibleWhilePlaying)
    }

    /** Stopping casting on purpose still hands the song back to the phone, which plays on. */
    @Test fun stoppingCastingHandsBackToThePhone() {
        val phone = FakePlayer().apply { playWhenReady = true }
        val mirror = mirror(phone)
        mirror.attach(FakeReceiver())

        mirror.detach()

        assertTrue(phone.playWhenReady)
        assertFalse(DspAudioProcessor.silenced)
        assertEquals(false, mirror.active)
    }
}
