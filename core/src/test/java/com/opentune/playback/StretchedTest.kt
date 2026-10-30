package com.opentune.playback

import com.opentune.data.settings.SoundSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When float output has to give way so Media3 can change the song's speed and pitch. */
class StretchedTest {
    @Test fun aPlainSongIsNotStretched() {
        assertFalse(isStretched(SoundSettings(), skipSilence = false, inRoom = false))
        // A slider left a hair off normal by rounding still counts as normal.
        assertFalse(isStretched(SoundSettings(speed = 1.0004f, pitch = 0.9996f), skipSilence = false, inRoom = false))
    }

    @Test fun speedPitchSilenceAndRoomsAllStretchIt() {
        assertTrue(isStretched(SoundSettings(speed = 1.25f), skipSilence = false, inRoom = false))
        assertTrue(isStretched(SoundSettings(pitch = 0.9f), skipSilence = false, inRoom = false))
        assertTrue(isStretched(SoundSettings(), skipSilence = true, inRoom = false))
        assertTrue(isStretched(SoundSettings(), skipSilence = false, inRoom = true))
    }
}
