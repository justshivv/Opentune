package com.opentune.playback.mix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixAnalysisTest {
    private val rate = 8_000
    private val hop = 80 // 10 ms

    /**
     * A made-up song: a bed of noise with a kick on every beat (a decaying
     * 60 Hz thump), [gain] shaping its level over time. Turned into features
     * the way the decoder does it.
     */
    private fun song(
        startMs: Long,
        lengthMs: Long,
        bpm: Double?,
        firstBeatMs: Double = 0.0,
        seed: Int = 1,
        /** The kick on the first beat of every [accentEvery] hits this much harder, as on the one of a bar. */
        accentEvery: Int = 0,
        accent: Double = 1.0,
        gain: (Long) -> Double = { 1.0 },
    ): Features {
        val random = Random(seed)
        val n = (lengthMs * rate / 1000).toInt()
        val period = bpm?.let { 60_000.0 / it }
        val alpha = (1 - exp(-2 * PI * 150 / rate)).toFloat()
        var lp1 = 0f; var lp2 = 0f
        val energy = ArrayList<Float>(); val low = ArrayList<Float>()
        var sum = 0.0; var lowSum = 0.0; var count = 0
        for (i in 0 until n) {
            val tMs = startMs + i * 1000.0 / rate
            var x = (random.nextFloat() * 2 - 1) * 0.05
            if (period != null && tMs >= firstBeatMs) {
                val since = (tMs - firstBeatMs) % period
                val n = ((tMs - firstBeatMs) / period).toLong()
                val hit = if (accentEvery > 0 && n % accentEvery == 0L) accent else 1.0
                if (since < 120) x += 0.6 * hit * exp(-since / 30.0) * sin(2 * PI * 60 * since / 1000.0)
            }
            x *= gain(tMs.toLong())
            val f = x.toFloat()
            lp1 += alpha * (f - lp1); lp2 += alpha * (lp1 - lp2)
            sum += f * f; lowSum += lp2 * lp2
            if (++count == hop) {
                energy.add((sum / hop).toFloat()); low.add((lowSum / hop).toFloat())
                sum = 0.0; lowSum = 0.0; count = 0
            }
        }
        return Features.of(startMs, 10.0, energy.toFloatArray(), low.toFloatArray())
    }

    @Test
    fun findsTheTempoAndWhereTheBeatsFall() {
        val f = song(0, 30_000, bpm = 124.0, firstBeatMs = 230.0)
        val beat = beatGrid(f)!!
        assertEquals(124.0, beat.bpm, 0.8)
        assertTrue("confidence ${beat.confidence}", beat.sure)
        // A beat falls within a hop or two of each real one.
        val real = 230.0 + 40 * 60_000.0 / 124
        assertTrue("beat at ${beat.nearest(real.toLong())} for $real", abs(beat.nearest(real.toLong()) - real) <= 25)
    }

    @Test
    fun hearsSlowAndFastSongsAtTheirOwnSpeed() {
        assertEquals(90.0, beatGrid(song(0, 30_000, bpm = 90.0))!!.bpm, 0.8)
        assertEquals(150.0, beatGrid(song(0, 30_000, bpm = 150.0))!!.bpm, 1.2)
    }

    @Test
    fun noBeatInNoise() {
        val beat = beatGrid(song(0, 30_000, bpm = null))
        assertTrue("noise heard as a beat: $beat", beat == null || !beat.sure)
    }

    @Test
    fun endingSkipsTrailingSilence() {
        val duration = 200_000L
        // Music until 194 s, then silence to the end of the file.
        val f = song(duration - 45_000, 45_000, bpm = 120.0) { t -> if (t < 194_000) 1.0 else 0.0 }
        val ending = analyzeEnding(f, duration)!!
        assertEquals(194_000.0, ending.endMs.toDouble(), 400.0)
        assertTrue("a hard stop has no long outro: $ending", ending.outroLengthMs < 1_000)
    }

    @Test
    fun endingFindsAFadeOut() {
        val duration = 240_000L
        // Full level until 228 s, then fading 40 dB over 10 s, then silence.
        val f = song(duration - 45_000, 45_000, bpm = 100.0) { t ->
            when {
                t < 228_000 -> 1.0
                t < 238_000 -> Math.pow(10.0, -2.0 * (t - 228_000) / 10_000.0)
                else -> 0.0
            }
        }
        val ending = analyzeEnding(f, duration)!!
        assertTrue("fade-out not found: $ending", ending.outroLengthMs in 4_000..11_000)
        assertTrue(ending.outroMs in 227_000..233_000)
    }

    @Test
    fun openingSkipsLeadingSilenceAndNoticesAQuietIntro() {
        val quiet = song(0, 30_000, bpm = 120.0, firstBeatMs = 1_500.0) { t ->
            when {
                t < 1_200 -> 0.0
                t < 9_000 -> 0.2
                else -> 1.0
            }
        }
        val o = analyzeOpening(quiet)!!
        assertEquals(1_200.0, o.startMs.toDouble(), 60.0)
        assertTrue(o.quietIntro)

        val punchy = analyzeOpening(song(0, 30_000, bpm = 120.0))!!
        assertTrue(punchy.startMs < 50)
        assertFalse(punchy.quietIntro)
    }

    @Test
    fun silenceIsNotASong() {
        assertNull(analyzeOpening(song(0, 30_000, bpm = null) { 0.0 }))
    }

    @Test
    fun automixBlendsAcrossAFadeOutOnTheBeat() {
        val duration = 240_000L
        val beat = Beat(500.0, 0.0, 3f)
        val ending = Ending(endMs = 238_000, outroMs = 230_000, beat = beat)
        val opening = Opening(startMs = 400, quietIntro = false, beat = Beat(505.0, 400.0, 3f))
        val plan = automixPlan(duration, ending, opening)
        // Eight seconds of fade: four bars of 2 s.
        assertEquals(8_000L, plan.blendMs)
        assertEquals(0L, plan.mixAtMs % 500)
        assertEquals(238_000L, plan.outEndMs)
        assertEquals(400L, plan.inFromMs)
        assertEquals(500.0, plan.beatMs!!, 0.0)
        assertTrue(plan.bassSwap)
    }

    @Test
    fun automixKeepsAHardStopShortAndSkipsSilence() {
        val plan = automixPlan(200_000, Ending(195_000, 195_000, null), Opening(800, quietIntro = false, beat = null))
        assertEquals(195_000L, plan.outEndMs)
        assertTrue(plan.blendMs in 1_500..4_000)
        assertEquals(195_000L - plan.blendMs, plan.mixAtMs)
        assertEquals(800L, plan.inFromMs)
        assertNull(plan.beatMs)
    }

    @Test
    fun automixWontStepSongsOfDifferentTempos() {
        val plan = automixPlan(200_000, Ending(198_000, 198_000, Beat(500.0, 0.0, 3f)), Opening(0, false, Beat(400.0, 0.0, 3f)))
        assertNull(plan.beatMs)
    }

    @Test
    fun crossfadeTrimsSilenceButKeepsItsLength() {
        val plan = crossfadePlan(200_000, 6_000, Ending(196_000, 196_000, null), Opening(500, false, null))
        assertEquals(190_000L, plan.mixAtMs)
        assertEquals(6_000L, plan.blendMs)
        assertEquals(500L, plan.inFromMs)
        val plain = crossfadePlan(200_000, 6_000, null, null)
        assertEquals(194_000L, plain.mixAtMs)
        assertEquals(0L, plain.inFromMs)
    }

    @Test
    fun stepCorrectionMovesTheIncomingSongOntoTheBeat() {
        val plan = MixPlan(mixAtMs = 100_000, outEndMs = 108_000, inFromMs = 0, blendMs = 8_000, beatMs = 500.0, outBeatMs = 100_000, inBeatMs = 250)
        // In step: the incoming song's beat at 250 when the outgoing one is on its beat.
        assertEquals(0L, stepCorrection(plan, 100_500, 750))
        assertEquals(0L, stepCorrection(plan, 101_000, 3_250))
        // 40 ms behind: move it on by 40.
        assertEquals(40L, stepCorrection(plan, 100_500, 710))
        // 60 ms ahead: back by 60.
        assertEquals(-60L, stepCorrection(plan, 100_500, 810))
        // Never more than half a beat either way.
        assertTrue(abs(stepCorrection(plan, 100_500, 1_000)) <= 250)
        assertEquals(0L, stepCorrection(plan.copy(beatMs = null), 100_500, 810))
    }

    @Test
    fun blendHoldsTheLevelAndTradesTheBass() {
        for (i in 0..10) {
            val (a, b) = blendGains(i / 10f)
            assertEquals(1.0, (a * a + b * b).toDouble(), 1e-5)
        }
        assertEquals(0f, bassCuts(0f).first, 0f)
        assertTrue(bassCuts(0f).second > 100f)
        assertTrue(bassCuts(1f).first > 100f)
        assertEquals(0f, bassCuts(1f).second, 0f)
    }

    @Test
    fun findsWhereTheBarsStart() {
        // 120 BPM, the one of each bar (every fourth beat from 250 ms) hit harder.
        val f = song(0, 30_000, bpm = 120.0, firstBeatMs = 250.0, accentEvery = 4, accent = 1.8)
        val beat = withDownbeat(f, beatGrid(f)!!, 0, 30_000)
        assertEquals(4, beat.groupBeats)
        // A bar starts at 250 + 2000·k: the start found is one of them, give or take a hop or two.
        val off = ((beat.downbeatMs!! - 250.0) % 2_000.0 + 2_000.0) % 2_000.0
        assertTrue("bar starts at ${beat.downbeatMs}", off <= 25 || off >= 1_975)
        // Bar lines from it.
        val line = ((beat.groupNearest(10_300) - 250) % 2_000 + 2_000) % 2_000
        assertTrue("bar line at ${beat.groupNearest(10_300)}", line <= 25 || line >= 1_975)
    }

    @Test
    fun evenBeatsAreNotBars() {
        val f = song(0, 30_000, bpm = 120.0)
        val beat = withDownbeat(f, beatGrid(f)!!, 0, 30_000)
        assertNull(beat.downbeatMs)
        assertEquals(1, beat.groupBeats)
    }

    @Test
    fun automixPutsBarOnBar() {
        val out = Beat(500.0, 0.0, 3f, downbeatMs = 1_000.0, groupBeats = 4)
        val inc = Beat(500.0, 300.0, 3f, downbeatMs = 800.0, groupBeats = 4)
        val plan = automixPlan(240_000, Ending(236_000, 236_000, out), Opening(300, false, inc))
        assertEquals(4, plan.alignBeats)
        // The blend starts on the one of an outgoing bar, the incoming song on the one of its first bar.
        assertEquals(0L, (plan.mixAtMs - 1_000) % 2_000)
        assertEquals(800L, plan.inFromMs)
        assertEquals(800L, plan.inBeatMs)
        // A bar's correction can reach half a bar, not just half a beat.
        assertEquals(-600L, stepCorrection(plan, plan.mixAtMs, 1_400))
    }

    @Test
    fun aSongThatFadesItselfKeepsItsLevelAtFirst() {
        val (early, _) = blendGains(0.3f, outFades = true)
        assertEquals(1f, early, 1e-4f)
        assertEquals(0f, blendGains(1f, outFades = true).first, 1e-4f)
        val plan = automixPlan(240_000, Ending(238_000, 230_000, Beat(500.0, 0.0, 3f)), Opening(0, false, null))
        assertTrue(plan.outFades)
    }

    @Test
    fun levelsMeetThroughTheBlend() {
        // The incoming song 6 dB louder: it comes in 6 dB down and ends at its own level.
        val (o0, i0) = levelTrims(6f, 0f)
        assertEquals(1f, o0, 0f)
        assertEquals(0.501f, i0, 0.002f)
        assertEquals(1f, levelTrims(6f, 1f).second, 1e-4f)
        // The outgoing one louder: brought down as it goes, never anything raised.
        assertEquals(1f, levelTrims(-6f, 0f).first, 1e-4f)
        assertEquals(0.501f, levelTrims(-6f, 1f).first, 0.002f)
        assertEquals(1f, levelTrims(-6f, 0.5f).second, 0f)
        val plan = automixPlan(200_000, Ending(198_000, 198_000, null, levelDb = -20f), Opening(0, false, null, levelDb = -12f))
        assertEquals(8f, plan.levelDb, 1e-4f)
    }

    @Test
    fun theBassChangesHandsOnABarLine() {
        val plan = automixPlan(240_000, Ending(238_000, 230_000, Beat(500.0, 0.0, 3f)), Opening(0, false, null))
        // Eight seconds, four bars: the swap on the bar line half way, over about two beats.
        assertEquals(0.5f, plan.swapAt, 1e-4f)
        assertEquals(0.125f, plan.swapWidth, 1e-4f)
        val (outBefore, inBefore) = bassCuts(0.4f, plan.swapAt, plan.swapWidth)
        assertEquals(0f, outBefore, 0f)
        assertEquals(200f, inBefore, 0f)
        val (outAfter, inAfter) = bassCuts(0.6f, plan.swapAt, plan.swapWidth)
        assertEquals(200f, outAfter, 0f)
        assertEquals(0f, inAfter, 0f)
    }
}
