package com.opentune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionSteadierTest {
    @Test fun slipsBackAreAddedBackAndAddUp() {
        val s = PositionSteadier()
        assertEquals(10_000L, s.steady("a", 10_000))
        assertEquals(12_000L, s.steady("a", 12_000))
        // The output corrected its timestamps: the player says 1.1 s less than before.
        assertEquals(12_000L, s.steady("a", 10_900))
        assertEquals(14_100L, s.steady("a", 13_000))
        // And again, 2.4 s: both slips stay added.
        assertEquals(14_100L, s.steady("a", 11_700))
        assertEquals(17_000L, s.steady("a", 14_600))
    }

    @Test fun aSeekANewSongOrALongStepBackStartAgain() {
        val s = PositionSteadier()
        s.steady("a", 10_000)
        s.steady("a", 8_000) // a slip: 2 s kept
        assertEquals(12_000L, s.steady("a", 10_000))
        s.reset()
        assertEquals(3_000L, s.steady("a", 3_000))
        s.steady("a", 50_000)
        // 40 s back with no seek told: not a slip, followed.
        assertEquals(10_000L, s.steady("a", 10_000))
        s.steady("a", 9_000)
        assertEquals(500L, s.steady("b", 500))
    }

    @Test fun pausedStaysPut() {
        val s = PositionSteadier()
        s.steady("a", 5_000)
        assertEquals(5_000L, s.steady("a", 5_000))
        assertEquals(5_000L, s.steady("a", 5_000))
    }
}
