package com.opentune.playback

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class LossyHoldBackTest {
    private val wantsMore = object : LoadControl {
        override fun getAllocator(playerId: PlayerId): Allocator = throw UnsupportedOperationException()
        override fun shouldContinueLoading(parameters: LoadControl.Parameters) = true
    }
    private fun ahead(seconds: Long) = LoadControl.Parameters(PlayerId.UNSET, Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID,
        0L, seconds * 1_000_000L, 1f, true, false, 0L, 0L)

    @Test fun whileAFlacMayReplaceItTheStreamIsFetchedOnlyAMinuteAhead() {
        var searching = true
        val control = LossyHoldBack(wantsMore, 60_000_000L) { searching }
        assertTrue(control.shouldContinueLoading(ahead(30)))
        assertFalse(control.shouldContinueLoading(ahead(60)))
        searching = false
        assertTrue(control.shouldContinueLoading(ahead(600)))
    }

    /** With the real load control behind it, as the player has: the player is made known to it first. */
    @Test fun theRealLoadControlBehindItIsToldAboutThePlayer() {
        val real = androidx.media3.exoplayer.DefaultLoadControl()
        val control = LossyHoldBack(real, 60_000_000L) { false }
        control.onPrepared(PlayerId.UNSET)
        // A one-song timeline, as the player passes.
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(240_000_000L, true, false, false, null,
            androidx.media3.common.MediaItem.fromUri("https://cdn.example/song.flac"))
        val period = androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
        val params = LoadControl.Parameters(PlayerId.UNSET, timeline, period, 0L, 5_000_000L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        // Threw before: onPrepared went to Java's default, which throws, and the app crashed on opening.
        assertTrue(control.shouldContinueLoading(params))
        assertEquals(real.getBackBufferDurationUs(PlayerId.UNSET), control.getBackBufferDurationUs(PlayerId.UNSET))
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)
    }

    /** The real player, built and prepared with it, as the playback service does when the app opens. */
    @Test fun thePlayerBuildsAndPreparesWithIt() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setLoadControl(LossyHoldBack(androidx.media3.exoplayer.DefaultLoadControl(), 60_000_000L) { false })
            .build()
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri("asset:///missing.flac"))
        player.prepare()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        player.stop()
        player.release()
    }
}
