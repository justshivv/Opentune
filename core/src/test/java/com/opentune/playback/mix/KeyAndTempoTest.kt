package com.opentune.playback.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAndTempoTest {
    private val rate = 11_025

    /** Notes (MIDI numbers) played together for [seconds], with a couple of overtones each. */
    private fun chords(seconds: Int, vararg progression: IntArray): FloatArray {
        val n = rate * seconds
        val each = n / progression.size
        return FloatArray(n) { i ->
            val notes = progression[(i / each).coerceAtMost(progression.lastIndex)]
            var x = 0.0
            for (m in notes) {
                val f = 440.0 * 2.0.pow((m - 69) / 12.0)
                for (h in 1..3) x += sin(2 * PI * f * h * i / rate) / h
            }
            (x / (notes.size * 2)).toFloat()
        }
    }

    @Test fun hearsCMajor() {
        // C, F, G, C: the plainest major progression.
        val s = chords(16, intArrayOf(60, 64, 67), intArrayOf(65, 69, 72), intArrayOf(67, 71, 74), intArrayOf(60, 64, 67))
        val key = estimateKey(chroma(s, s.size, rate))
        assertNotNull(key)
        assertEquals(0, key!!.tonic)
        assertEquals(false, key.minor)
        assertEquals("8B", key.camelot)
    }

    @Test fun hearsAMinor() {
        // Am, Dm, E, Am.
        val s = chords(16, intArrayOf(57, 60, 64), intArrayOf(62, 65, 69), intArrayOf(64, 68, 71), intArrayOf(57, 60, 64))
        val key = estimateKey(chroma(s, s.size, rate))!!
        assertEquals(9, key.tonic)
        assertTrue(key.minor)
        assertEquals("8A", key.camelot)
    }

    @Test fun camelotWheel() {
        assertEquals("9B", Key(7, false, 1f).camelot) // G major
        assertEquals("10A", Key(11, true, 1f).camelot) // B minor
        assertEquals("5A", Key(0, true, 1f).camelot) // C minor
        assertEquals("7B", Key(5, false, 1f).camelot) // F major
        assertEquals(0, Key(0, false, 1f).distance(Key(9, true, 1f))) // relatives
        assertEquals(1, Key(0, false, 1f).distance(Key(7, false, 1f))) // neighbours
        assertTrue(Key(0, false, 1f).distance(Key(6, false, 1f)) > 1) // a clash
        assertNull(estimateKey(FloatArray(12)))
    }

    @Test fun closeTemposAreMatched() {
        val plan = automixPlan(200_000, Ending(198_000, 198_000, Beat(468.75, 0.0, 3f)), Opening(0, false, Beat(444.4, 0.0, 3f)))
        assertNotNull(plan.beatMs)
        // 128 into 135 bpm: the outgoing song sped up about 5.5%.
        assertEquals(1.055f, plan.outSpeed, 0.005f)
        assertEquals(444.4, plan.inBeatPeriodMs!!, 0.01)
    }

    @Test fun stepsAcrossTwoTempos() {
        val plan = MixPlan(mixAtMs = 100_000, outEndMs = 108_000, inFromMs = 0, blendMs = 8_000, beatMs = 500.0, outBeatMs = 100_000, inBeatMs = 0, inBeatPeriodMs = 400.0)
        // Both on their beat: nothing to move.
        assertEquals(0L, stepCorrection(plan, 100_500, 800))
        // The outgoing song half way through its beat, the incoming on its own: move half an incoming beat.
        assertEquals(200L, abs(stepCorrection(plan, 100_250, 800)))
    }

    @Test fun clashingKeysBlendShort() {
        val clash = automixPlan(200_000,
            Ending(198_000, 198_000, null, key = Key(0, false, 0.2f)),
            Opening(0, quietIntro = true, beat = null, key = Key(6, false, 0.2f)))
        val calm = automixPlan(200_000,
            Ending(198_000, 198_000, null, key = Key(0, false, 0.2f)),
            Opening(0, quietIntro = true, beat = null, key = Key(7, false, 0.2f)))
        assertTrue("${clash.blendMs} vs ${calm.blendMs}", clash.blendMs < calm.blendMs)
    }
}
