package com.opentune.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.opentune.data.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The session's player with fades on: what the notification shows, when the player really
 * pauses or skips, and that the system and the service's own calls still act at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FadingSessionPlayerTest {
    /** Just enough of a player: plays at once, three songs, every change told to its listeners. */
    private class Fake {
        var playWhenReady = true
        var volume = 1f
        var index = 0
        var pauses = 0
        val listeners = mutableListOf<Player.Listener>()

        fun setPlaying(on: Boolean) {
            if (on == playWhenReady) return
            playWhenReady = on
            if (!on) pauses++
            listeners.toList().forEach {
                it.onPlayWhenReadyChanged(on, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                it.onIsPlayingChanged(on)
            }
        }

        val player: ExoPlayer = java.lang.reflect.Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, m, args ->
            when (m.name) {
                "getPlayWhenReady", "isPlaying" -> playWhenReady
                "play" -> setPlaying(true)
                "pause" -> setPlaying(false)
                "setPlayWhenReady" -> setPlaying(args!![0] as Boolean)
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; Unit }
                "getMediaItemCount" -> 3
                "getCurrentMediaItemIndex" -> index
                "hasNextMediaItem" -> index < 2
                "getNextMediaItemIndex" -> if (index < 2) index + 1 else -1
                "hasPreviousMediaItem" -> index > 0
                "getPreviousMediaItemIndex" -> if (index > 0) index - 1 else -1
                "getCurrentPosition" -> 60_000L
                "getMaxSeekToPreviousPosition" -> 3_000L
                "getPlaybackState" -> Player.STATE_READY
                "seekToDefaultPosition", "seekTo" -> { if (args != null && args.isNotEmpty() && args[0] is Int) index = args[0] as Int; Unit }
                "seekToNext", "seekToNextMediaItem" -> { if (index < 2) index++; Unit }
                "addListener" -> { listeners += args!![0] as Player.Listener; Unit }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; Unit }
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "Fake"
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as ExoPlayer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fake = Fake()
    private var pressed = true
    private var canFade = true
    private var blends = false
    private lateinit var fades: TransportFades
    private lateinit var session: FadingSessionPlayer
    /** What the session was told about play/pause, last first. */
    private val told = mutableListOf<Boolean>()

    @Before fun setUp() {
        AppSettings.updatePlayback { it.copy(transportFade = true, transportFadeMs = 1_000) }
        fades = TransportFades(scope, fake.player, canFade = { canFade }, blend = { _, _, _ -> blends })
        session = FadingSessionPlayer(fake.player, fades) { pressed }
        session.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { told.add(0, playWhenReady) }
        })
    }

    @After fun tearDown() {
        fades.release()
        scope.cancel()
        AppSettings.updatePlayback { it.copy(transportFade = false, transportFadeMs = 1_000) }
    }

    private fun after(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    @Test fun pauseShowsPausedAtOnceThenFadesAndPauses() {
        session.pause()
        // The notification shows Play straight away; the song is still fading.
        assertFalse(session.playWhenReady)
        assertFalse(session.isPlaying)
        assertEquals(false, told.first())
        assertTrue(fake.playWhenReady)
        after(500)
        assertTrue(fake.volume in 0.05f..0.95f)
        assertTrue(fake.playWhenReady)
        after(700)
        assertFalse(fake.playWhenReady)
        assertEquals(1f, fake.volume, 0f)
        assertFalse(fades.active)
    }

    @Test fun pauseAgainMidFadePausesNow() {
        session.pause()
        after(200)
        session.pause()
        assertFalse(fake.playWhenReady)
        assertEquals(1f, fake.volume, 0f)
    }

    @Test fun playMidFadeComesBackWithoutPausing() {
        session.pause()
        after(400)
        session.play()
        assertTrue(session.playWhenReady)
        assertEquals(true, told.first())
        after(1_000)
        assertEquals(0, fake.pauses)
        assertEquals(1f, fake.volume, 0f)
    }

    @Test fun playFromPausedStartsSilentAndRises() {
        fake.setPlaying(false)
        session.play()
        assertTrue(fake.playWhenReady)
        assertTrue(fake.volume < 0.05f)
        after(1_200)
        assertEquals(1f, fake.volume, 0f)
    }

    @Test fun theSystemPausingMidFadeWinsAndLeavesFullVolume() {
        // Audio focus lost, or headphones pulled out, while play fades in.
        fake.setPlaying(false)
        session.play()
        after(300)
        fake.setPlaying(false)
        assertFalse(fades.active)
        assertEquals(1f, fake.volume, 0f)
        after(1_000)
        assertFalse(fake.playWhenReady)
    }

    @Test fun aSkipFadesOutChangesThenRises() {
        session.seekToNext()
        assertEquals(0, fake.index)
        after(600)
        assertEquals(1, fake.index)
        assertTrue(fake.volume < 0.3f)
        after(1_200)
        assertEquals(1f, fake.volume, 0f)
        // Picked from the queue: the same.
        session.seekToDefaultPosition(0)
        after(600)
        assertEquals(0, fake.index)
    }

    @Test fun aBlendTakesTheSkipWithoutAFade() {
        blends = true
        session.seekToNext()
        assertFalse(fades.active)
        assertEquals(1f, fake.volume, 0f)
    }

    @Test fun theServicesOwnCallsActAtOnce() {
        pressed = false
        session.seekToNext()
        assertEquals(1, fake.index)
        session.pause()
        assertFalse(fake.playWhenReady)
        assertFalse(fades.active)
    }

    @Test fun whereTheVolumeIsNotOursNothingFades() {
        // Bit-perfect output, casting, a blend under way.
        canFade = false
        session.pause()
        assertFalse(fake.playWhenReady)
        session.play()
        assertTrue(fake.playWhenReady)
        assertEquals(1f, fake.volume, 0f)
    }

    @Test fun offActsAtOnce() {
        AppSettings.updatePlayback { it.copy(transportFade = false) }
        session.pause()
        assertFalse(fake.playWhenReady)
    }
}
