package com.opentune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dolby streams named as the player shows them. */
class DolbyNameTest {
    private fun info(mime: String?, codec: String? = null) = AudioFormatInfo(codec, null, 48_000, 6, mime = mime)

    @Test fun atmosInEac3AndAc4() {
        assertEquals("Dolby Atmos", dolbyName(info("audio/eac3-joc", "EAC3-JOC")))
        assertEquals("Dolby Atmos", dolbyName(info("audio/mp4a-latm", "EC+3")))
        assertEquals("Dolby Atmos", dolbyName(info("audio/ac4", "AC-4.02.01.03")))
    }

    @Test fun plainDolby() {
        assertEquals("Dolby Digital Plus", dolbyName(info("audio/eac3", "EC-3")))
        assertEquals("Dolby Digital", dolbyName(info("audio/ac3")))
    }

    @Test fun otherCodecsAreNotDolby() {
        assertNull(dolbyName(info("audio/flac", "FLAC")))
        assertNull(dolbyName(info("audio/opus", "OPUS")))
        assertNull(dolbyName(null))
    }
}
