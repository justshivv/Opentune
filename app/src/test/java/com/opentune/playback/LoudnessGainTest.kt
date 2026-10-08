package com.opentune.playback

import com.opentune.data.settings.VolumeLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class LoudnessGainTest {
    @Test fun normalBringsSongsToTheReference() {
        // A loud modern master, 7 dB over YouTube's reference, comes down 7 dB.
        assertEquals(-7.0, loudnessGainDb(7.0, VolumeLevel.NORMAL, speakerHold = false), 1e-9)
        // A quiet one is lifted, but only by 3 dB.
        assertEquals(3.0, loudnessGainDb(-6.0, VolumeLevel.NORMAL, speakerHold = false), 1e-9)
    }

    @Test fun loudSitsFiveDecibelsHigher() {
        assertEquals(-2.0, loudnessGainDb(7.0, VolumeLevel.LOUD, speakerHold = false), 1e-9)
        assertEquals(5.0, loudnessGainDb(0.0, VolumeLevel.LOUD, speakerHold = false), 1e-9)
        // Lifts stop at 9 dB however quiet the master.
        assertEquals(9.0, loudnessGainDb(-12.0, VolumeLevel.LOUD, speakerHold = false), 1e-9)
    }

    @Test fun quietSitsFiveDecibelsLower() {
        assertEquals(-12.0, loudnessGainDb(7.0, VolumeLevel.QUIET, speakerHold = false), 1e-9)
    }

    @Test fun theSpeakerOnlyEverLifts() {
        assertEquals(0.0, loudnessGainDb(7.0, VolumeLevel.LOUD, speakerHold = true), 1e-9)
        assertEquals(7.0, loudnessGainDb(-2.0, VolumeLevel.LOUD, speakerHold = true), 1e-9)
    }
}
