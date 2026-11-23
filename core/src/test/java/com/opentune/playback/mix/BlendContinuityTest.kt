package com.opentune.playback.mix

import org.junit.Assert.*
import org.junit.Test

class BlendContinuityTest {
    @Test fun bufferingAndPausesDoNotConsumeTheFade() {
        assertTrue(blendCanAdvance(true, true, false))
        assertFalse(blendCanAdvance(true, false, false))
        assertFalse(blendCanAdvance(false, false, false))
        assertFalse(blendCanAdvance(false, true, false))
        assertTrue(blendCanAdvance(false, true, true))
        assertFalse(blendCanAdvance(false, false, true))
    }

    @Test fun recoveryNeverMutesTheOnlyAudibleStream() {
        assertEquals(1f to 0f, bufferingGains(true, false))
        assertEquals(0f to 1f, bufferingGains(false, true))
        assertNull(bufferingGains(false, false))
        assertNull(bufferingGains(true, true))
    }

    @Test fun bufferingRecoveryDoesNotJumpToFullVolume() {
        val levels = BlendLevels(0.3f, 0.7f)
        val first = levels.towards(1f, 0f, 10)
        assertTrue(first.first > 0.3f && first.first < 0.36f)
        assertTrue(first.second < 0.7f && first.second > 0.64f)
        repeat(20) { levels.towards(1f, 0f, 10) }
        assertEquals(1f, levels.outgoing, 0.0001f)
        assertEquals(0f, levels.incoming, 0.0001f)
    }

    @Test fun normalEqualPowerCurveIsPreservedAtPlaybackCadence() {
        val levels = BlendLevels()
        for (ms in 10..6000 step 10) {
            val expected = blendGains(ms / 6000f)
            val actual = levels.towards(expected.first, expected.second, 10)
            assertEquals(expected.first, actual.first, 0.0001f)
            assertEquals(expected.second, actual.second, 0.0001f)
        }
    }

    @Test fun aLateBlendCarriesOnFromWhereThePlayingSongWas() {
        // Planned to meet at 180 s with the next song from 2 s; it started sounding at 182.5 s instead.
        val clock = BlendClock(outMs = 182_500, inMs = 2_000, outSpeed = 1f)
        assertEquals(182_500L, clock.at(2_000))
        assertEquals(183_000L, clock.at(2_500))
        assertEquals(184_000L, clock.at(3_500))
    }

    @Test fun aBeatSeekNeverSendsTheClockBack() {
        val clock = BlendClock(outMs = 100_000, inMs = 0, outSpeed = 1f)
        var last = clock.at(0)
        val shown = mutableListOf<Long>()
        // The next song is moved back 180 ms onto the beat after 300 ms, then plays on.
        for (r in listOf(100L, 200L, 300L, 120L, 220L, 320L, 700L, 800L)) {
            val t = clock.at(r)
            assertTrue("$t after $last", t >= last)
            last = t
            shown += t
        }
        // Held at the seek, then on at the song's pace from there.
        assertEquals(listOf(100_100L, 100_200L, 100_300L, 100_300L, 100_400L, 100_500L, 100_880L, 100_980L), shown)
    }

    @Test fun theClockRunsAtThePlayingSongsTempo() {
        // The next song runs at the playing one's tempo: a second of it is 1.05 s of the playing song.
        val clock = BlendClock(outMs = 50_000, inMs = 1_000, outSpeed = 1.05f)
        assertEquals(51_050L, clock.at(2_000))
    }
}
