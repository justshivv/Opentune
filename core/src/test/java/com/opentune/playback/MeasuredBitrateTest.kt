package com.opentune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A song's bitrate measured from its audio, and which figure is shown. */
class MeasuredBitrateTest {
    @Test fun measuredFromBytesAndSpan() {
        // A FLAC stream: 2.25 MB of frames over 20 s is 900 kbps.
        assertEquals(900, MeasuredBitrate.kbpsOf(2_250_000, 20_000_000))
        // Too short a stretch says nothing yet.
        assertNull(MeasuredBitrate.kbpsOf(100_000, 500_000))
    }

    @Test fun bestFigureFirst() {
        val flac = AudioFormatInfo("FLAC", null, 96_000, 2, 24, mime = "audio/flac")
        // The measurement beats a missing or stated figure.
        assertEquals(2_811, bestKbps(flac, 2_811, null))
        assertEquals(2_811, bestKbps(flac.copy(bitrateKbps = 3_000), 2_811, 3_100))
        // Nothing measured yet: what the stream says, then the source.
        assertEquals(3_000, bestKbps(flac.copy(bitrateKbps = 3_000), null, 3_100))
        assertEquals(3_100, bestKbps(flac, null, 3_100))
        // Uncompressed PCM is exact from its format: 24-bit, 96 kHz, stereo.
        assertEquals(4_608, bestKbps(AudioFormatInfo("RAW", null, 96_000, 2, 24, mime = "audio/raw"), null, null))
        assertNull(bestKbps(flac, null, null))
    }
}
