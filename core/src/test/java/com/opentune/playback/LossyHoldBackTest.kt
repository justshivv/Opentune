package com.opentune.playback

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
