package com.opentune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPatternsTest {
    @Test fun everyFallbackPatternIsAWellFormedWaveform() {
        for (p in Haptics.Pattern.entries) for (s in listOf(0.1f, 0.6f, 1f)) {
            val (timings, levels) = Haptics.waveform(p, s)
            assertEquals("$p timings and levels pair up", timings.size, levels.size)
            assertTrue("$p plays something", levels.any { it > 0 })
            assertTrue("$p levels are in range", levels.all { it in 0..255 })
            assertTrue("$p is short enough to feel like a tap", timings.sum() < 300)
        }
    }

    @Test fun thePlayerPatternsHitHard() {
        for (p in listOf(Haptics.Pattern.PLAY, Haptics.Pattern.PAUSE, Haptics.Pattern.NEXT, Haptics.Pattern.PREVIOUS, Haptics.Pattern.LIKE)) {
            assertEquals("$p reaches full strength", 255, Haptics.waveform(p, 0.3f).second.max())
        }
    }
}
