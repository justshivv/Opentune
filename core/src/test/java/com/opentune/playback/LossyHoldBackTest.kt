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
        override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId, bufferedDurationUs: Long) = true
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
    /** Two songs in a row, each 200 s: [a] playing, [b] ahead. */
    private val queue = object : Timeline() {
        val items = listOf(androidx.media3.common.MediaItem.Builder().setMediaId("a").setUri("https://cdn.example/a").build(),
            androidx.media3.common.MediaItem.Builder().setMediaId("b").setUri("https://cdn.example/b").build())
        override fun getWindowCount() = 2
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(windowIndex, items[windowIndex], null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0L, 200_000_000L, windowIndex, windowIndex, 0L)
        override fun getPeriodCount() = 2
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(periodIndex, periodIndex, periodIndex, 200_000_000L, 0L)
        override fun getIndexOfPeriod(uid: Any) = uid as? Int ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex
    }
    /** At [positionS] into song a, with [bufferedS] fetched past it, the loader on song [loading]. */
    private fun at(positionS: Long, bufferedS: Long, loading: Int) = LoadControl.Parameters(PlayerId.UNSET, queue,
        androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(loading), positionS * 1_000_000L, bufferedS * 1_000_000L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)

    @Test fun aFlacSongAheadIsFetchedOnlyItsFirstSecondsUnlessTheWholeIsAskedFor() {
        var whole = false
        val control = LossyHoldBack(wantsMore, 60_000_000L, 60_000_000L, flac = { it.mediaId == "b" },
            flacStartUs = 10_000_000L, playing = { "a" }, whole = { whole }) { false }
        // 190 s into a: 10 s of it left, so 15 s buffered is 5 s of b.
        assertTrue(control.shouldContinueLoading(at(190, 15, loading = 1)))
        assertFalse(control.shouldContinueLoading(at(190, 20, loading = 1)))
        // Nearer the end the same 10 s of b is all it gets.
        assertFalse(control.shouldContinueLoading(at(199, 11, loading = 1)))
        // ExoPlayer's own preload of the next song stops there too.
        assertFalse(control.shouldContinuePreloading(PlayerId.UNSET, queue, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(1), 10_000_000L))
        whole = true
        assertTrue(control.shouldContinueLoading(at(190, 120, loading = 1)))
        assertTrue(control.shouldContinuePreloading(PlayerId.UNSET, queue, androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(1), 30_000_000L))
    }

    @Test fun theFlacPlayingKeepsItsMinuteAheadAndAYouTubeSongAheadIsNotHeld() {
        val control = LossyHoldBack(wantsMore, 60_000_000L, 60_000_000L, flac = { it.mediaId == "a" },
            flacStartUs = 10_000_000L, playing = { "a" }) { false }
        assertTrue(control.shouldContinueLoading(at(0, 50, loading = 0)))
        assertFalse(control.shouldContinueLoading(at(0, 60, loading = 0)))
        assertTrue("b is YouTube's: fetched as before", control.shouldContinueLoading(at(190, 120, loading = 1)))
    }
}
