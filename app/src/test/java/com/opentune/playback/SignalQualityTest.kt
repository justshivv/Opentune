package com.opentune.playback

import com.opentune.data.NerdStats
import com.opentune.ui.player.signalQuality
import org.junit.Assert.*
import org.junit.Test

class SignalQualityTest {
    @Test fun displaysVerifiedFlacPrecisionAndActualFormat() {
        val source = NerdStats.Source("Qobuz", bits = 24, sampleRate = 96_000)
        val text = signalQuality(AudioFormatInfo("FLAC", null, 96_000, 2), source)
        assertTrue(text.contains("24-bit"))
        assertTrue(text.contains("96000 Hz"))
        assertTrue(text.contains("lossless"))
        assertTrue(text.contains("bitrate not reported"))
        assertFalse(text.contains("320 kbps"))
    }

    @Test fun showsActualBitrateInsteadOfTheConfiguredQualityCeiling() {
        val text = signalQuality(AudioFormatInfo("OPUS", 156, 48_000, 2), NerdStats.Source("YouTube · InnerTubeX"))
        assertTrue(text.contains("156 kbps"))
        assertFalse(text.contains("lossless"))
        assertFalse(text.contains("24-bit"))
        assertEquals("Waiting for the actual stream format", signalQuality(null, null))
    }
}
