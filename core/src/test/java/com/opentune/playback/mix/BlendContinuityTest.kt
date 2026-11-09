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
}
