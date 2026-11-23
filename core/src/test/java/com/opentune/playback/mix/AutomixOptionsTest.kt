package com.opentune.playback.mix

import com.opentune.data.settings.TempoMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automix's controls: its length, how far it matches tempos, and Always mix. */
class AutomixOptionsTest {
    private val ending = Ending(198_000, 198_000, Beat(468.75, 0.0, 3f))
    /** 128 into 135 bpm: about 5.5 % apart. */
    private val faster = Opening(0, false, Beat(444.4, 0.0, 3f))
    /** 128 into 130 bpm: under 2 % apart. */
    private val near = Opening(0, false, Beat(461.5, 0.0, 3f))

    @Test fun theDefaultsAreAutomixAsItWas() {
        assertEquals(automixPlan(200_000, ending, faster), automixPlan(200_000, ending, faster, AutomixOptions()))
        assertEquals(TempoMatch.FULL.maxStretch, AutomixOptions().tempoStretch, 0.0)
    }

    @Test fun aFixedLengthWithoutABeatIsThatLong() {
        val plan = automixPlan(200_000, Ending(198_000, 198_000, null), Opening(0, false, null), AutomixOptions(lengthMs = 8_000))
        assertEquals(8_000L, plan.blendMs)
        assertEquals(190_000L, plan.mixAtMs)
        // Unread songs too.
        assertEquals(4_000L, automixPlan(200_000, null, null, AutomixOptions(lengthMs = 4_000)).blendMs)
    }

    @Test fun aFixedLengthOnABeatIsTheNearestWholeBars() {
        // Bars of 1.875 s: 8 s is four of them, 7.5 s.
        val plan = automixPlan(200_000, ending, faster, AutomixOptions(lengthMs = 8_000))
        assertEquals(7_500.0, plan.blendMs.toDouble(), 2.0)
        // 12 s is six bars, which Auto never picks (it keeps to 1, 2, 4 or 8).
        assertEquals(11_250.0, automixPlan(200_000, ending, faster, AutomixOptions(lengthMs = 12_000)).blendMs.toDouble(), 2.0)
    }

    @Test fun tempoMatchOffNeverStretches() {
        val plan = automixPlan(200_000, ending, faster, AutomixOptions(tempoStretch = TempoMatch.OFF.maxStretch))
        assertNull(plan.beatMs)
        assertEquals(1f, plan.outSpeed, 0f)
        // Songs already at one tempo are still kept in step, at their own speed.
        val same = automixPlan(200_000, ending, Opening(0, false, Beat(468.0, 0.0, 3f)), AutomixOptions(tempoStretch = TempoMatch.OFF.maxStretch))
        assertNotNull(same.beatMs)
        assertEquals(1f, same.outSpeed, 0f)
    }

    @Test fun gentleMatchesOnlyCloseTempos() {
        val gentle = AutomixOptions(tempoStretch = TempoMatch.GENTLE.maxStretch)
        assertNull(automixPlan(200_000, ending, faster, gentle).beatMs)
        val plan = automixPlan(200_000, ending, near, gentle)
        assertNotNull(plan.beatMs)
        assertTrue(plan.outSpeed != 1f)
        // Full takes both.
        assertNotNull(automixPlan(200_000, ending, faster, AutomixOptions(tempoStretch = TempoMatch.FULL.maxStretch)).beatMs)
    }

    @Test fun alwaysMixKeepsAClashFull() {
        val clashing = Ending(198_000, 198_000, null, key = Key(0, false, 0.2f)) to Opening(0, quietIntro = true, beat = null, key = Key(6, false, 0.2f))
        val calm = Ending(198_000, 198_000, null, key = Key(0, false, 0.2f)) to Opening(0, quietIntro = true, beat = null, key = Key(7, false, 0.2f))
        val safe = automixPlan(200_000, clashing.first, clashing.second)
        val always = automixPlan(200_000, clashing.first, clashing.second, AutomixOptions(alwaysMix = true))
        val usual = automixPlan(200_000, calm.first, calm.second)
        assertTrue(safe.blendMs < usual.blendMs)
        assertEquals(usual.blendMs, always.blendMs)
    }

    @Test fun alwaysMixUsesABeatThatIsNotQuiteSure() {
        val unsureOut = Ending(198_000, 198_000, Beat(500.0, 0.0, 1.5f))
        val unsureIn = Opening(0, false, Beat(500.0, 0.0, 1.5f))
        val safe = automixPlan(200_000, unsureOut, unsureIn)
        assertNull(safe.beatMs)
        val always = automixPlan(200_000, unsureOut, unsureIn, AutomixOptions(alwaysMix = true))
        assertNotNull(always.beatMs)
        // Whole bars of 2 s.
        assertEquals(0L, always.blendMs % 2_000)
        // A beat that's hardly there still isn't trusted.
        assertNull(automixPlan(200_000, Ending(198_000, 198_000, Beat(500.0, 0.0, 1.1f)), Opening(0, false, Beat(500.0, 0.0, 1.1f)), AutomixOptions(alwaysMix = true)).beatMs)
    }
}
