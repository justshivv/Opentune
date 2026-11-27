package com.opentune.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongRingTest {
    private val w = 400f
    private val h = 400f
    private val band = 30f

    @Test
    fun aTouchOnTheRingFindsItsPlaceInTheSong() {
        // Straight down is halfway round; just right of the clock's gap is the start.
        assertEquals(0.5f, SongTime.fractionAt(200f, 399f, w, h, round = true, band = band)!!, 0.01f)
        val start = SongTime.fractionAt(200f + 199f * kotlin.math.sin(Math.toRadians(25.0)).toFloat(), 200f - 199f * kotlin.math.cos(Math.toRadians(25.0)).toFloat(), w, h, true, band)!!
        assertEquals(0f, start, 0.01f)
        // Right, three o'clock: a quarter round from the top, less the gap's half, over the ring's length.
        assertEquals((90f - 24f) / 312f, SongTime.fractionAt(399f, 200f, w, h, true, band)!!, 0.01f)
    }

    @Test
    fun theClocksGapGoesToTheNearerEnd() {
        assertEquals(0f, SongTime.fractionAt(205f, 1f, w, h, true, band)!!, 0f)
        assertEquals(1f, SongTime.fractionAt(195f, 1f, w, h, true, band)!!, 0f)
    }

    @Test
    fun aTouchAwayFromTheRingIsntASeek() {
        assertNull(SongTime.fractionAt(200f, 200f, w, h, true, band))
        assertNull(SongTime.fractionAt(200f, 300f, w, h, true, band))
    }

    @Test
    fun onASquareWatchTheLineAlongTheBottomCounts() {
        assertEquals(0.5f, SongTime.fractionAt(200f, 395f, w, h, round = false, band = band)!!, 0.01f)
        assertEquals(0f, SongTime.fractionAt(0f, 395f, w, h, false, band)!!, 0f)
        assertNull(SongTime.fractionAt(200f, 200f, w, h, false, band))
    }
}
