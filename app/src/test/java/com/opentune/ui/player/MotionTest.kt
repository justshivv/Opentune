package com.opentune.ui.player

import com.opentune.ui.components.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test fun echoWordsStayReadableAndReturnToRest() {
        for (time in listOf(-1f, 0f, 1f, 2f)) {
            val pose = wordPose(WordMotion.ECHO, time)
            assertEquals(0f, pose.lift, 1e-3f)
            assertEquals(1f, pose.scale, 1e-3f)
            assertEquals(1f, pose.alpha, 0f)
        }
        val peak = wordPose(WordMotion.ECHO, 0.5f)
        assertEquals(4f, peak.lift, 1e-3f)
        assertEquals(1.015f, peak.scale, 1e-3f)
        assertTrue(peak.glow in 0f..0.5f)
    }
    @Test fun bounceHopsAndLands() {
        assertEquals(0f, wordPose(WordMotion.BOUNCE, 0f).lift, 1e-3f)
        assertEquals(7f, wordPose(WordMotion.BOUNCE, 0.5f).lift, 1e-3f)
        assertEquals(0f, wordPose(WordMotion.BOUNCE, 1f).lift, 1e-3f)
        assertEquals(0f, wordPose(WordMotion.BOUNCE, 3f).lift, 1e-3f) // long after: at rest
    }

    @Test fun popSwellsThenSettles() {
        val mid = wordPose(WordMotion.POP, 0.5f)
        assertEquals(1.14f, mid.scale, 1e-3f)
        assertEquals(1f, mid.glow, 1e-3f)
        assertEquals(1f, wordPose(WordMotion.POP, 1f).scale, 1e-3f)
    }

    @Test fun revealHidesWordsUntilTheyreSung() {
        assertEquals(0f, wordPose(WordMotion.REVEAL, -0.2f).alpha, 0f)
        val starting = wordPose(WordMotion.REVEAL, 0.1f)
        assertTrue(starting.alpha in 0.2f..0.4f)
        assertTrue("comes up from below", starting.lift < 0f)
        val done = wordPose(WordMotion.REVEAL, 1f)
        assertEquals(1f, done.alpha, 0f)
        assertEquals(0f, done.lift, 1e-3f)
    }

    @Test fun haloBarsRestWhenPaused() {
        repeat(72) { assertEquals(0.08f, haloLevel(it, 12f, playing = false), 0f) }
        val levels = (0 until 72).map { haloLevel(it, 3.3f, playing = true) }
        assertTrue(levels.all { it in 0f..1f })
        assertTrue("they vary", levels.max() - levels.min() > 0.3f)
    }

    @Test fun hapticStrengthMapsToWords() {
        assertEquals("Off", Haptics.label(0f))
        assertEquals("Light", Haptics.label(0.3f))
        assertEquals("Medium", Haptics.label(0.6f))
        assertEquals("Strong", Haptics.label(1f))
    }
}
