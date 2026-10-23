package com.opentune.ui.player

import com.opentune.data.NerdStats
import com.opentune.playback.AudioFormatInfo
import org.junit.Assert.*
import org.junit.Test

class SourceQualityBadgeTest {
    @Test fun unknownQualityNeverClaimsLossless() {
        assertEquals("Resolving audio…", sourceQualityLabel(null, null))
        assertEquals("InnerTube · quality pending", sourceQualityLabel(NerdStats.Source("InnerTube"), null))
    }
    @Test fun showsRealLosslessPrecisionAndLossyBitrate() {
        assertEquals("Qobuz · FLAC · 24-bit · 96 kHz", sourceQualityLabel(NerdStats.Source("Qobuz",24,96000), AudioFormatInfo("FLAC",null,96000,2,24)))
        assertEquals("JioSaavn · AAC · 320 kbps", sourceQualityLabel(NerdStats.Source("JioSaavn",kbps=320), AudioFormatInfo("AAC",null,44100,2)))
    }

    @Test fun decoderWinsOverProviderClaims() {
        val advertised = NerdStats.Source("Add-on", 24, 192000)
        assertEquals("Add-on · quality pending", sourceQualityLabel(advertised, null))
        assertEquals("Add-on · FLAC · 16-bit · 44.1 kHz", sourceQualityLabel(advertised, AudioFormatInfo("FLAC", null, 44100, 2, 16)))
        assertFalse(isHighestQuality(advertised, AudioFormatInfo("AAC", 256, 44100, 2)))
    }
    @Test fun onlyLosslessCountsAsTheHighestQuality() {
        org.junit.Assert.assertFalse(isHighestQuality(NerdStats.Source("Qobuz", 24, 96000), null))
        org.junit.Assert.assertTrue(isHighestQuality(NerdStats.Source("Tidal"), com.opentune.playback.AudioFormatInfo("FLAC", null, 44100, 2, 16)))
        org.junit.Assert.assertFalse(isHighestQuality(NerdStats.Source("YouTube · InnerTube", kbps = 256), com.opentune.playback.AudioFormatInfo("AAC", 256, 44100, 2)))
        org.junit.Assert.assertFalse(isHighestQuality(NerdStats.Source("Checking highest quality"), null))
        org.junit.Assert.assertFalse(isHighestQuality(null, null))
    }

    @Test fun qualityTextUsesMeasuredFormatAndRealUpgradeState() {
        val source = NerdStats.Source("Community source",24,192000,320)
        assertEquals("High quality · 320 kbps · upgrading", qualityStatusLabel(source, AudioFormatInfo("AAC",320,44100,2), true))
        assertEquals("Lossless · 16-bit/44.1 kHz", qualityStatusLabel(source, AudioFormatInfo("FLAC",null,44100,2,16), false))
        assertEquals("Hi-Res Lossless · 24-bit/96 kHz", qualityStatusLabel(source, AudioFormatInfo("FLAC",null,96000,2,24), false))
        assertEquals("Upgrading quality", qualityStatusLabel(null, null, true))
    }

    @Test fun jioSaavnAndYouTubeShowTheirTierWithoutTheBitrate() {
        assertEquals("High Quality", qualityStatusLabel(NerdStats.Source("JioSaavn", kbps = 320), AudioFormatInfo("MP4A.40.2", 330, 44100, 2), false))
        assertEquals("Good Quality", qualityStatusLabel(NerdStats.Source("YouTube · InnerTube", kbps = 165), AudioFormatInfo("OPUS", 165, 48000, 2), false))
        assertEquals("Good Quality · upgrading", qualityStatusLabel(NerdStats.Source("YouTube · InnerTube"), AudioFormatInfo("OPUS", 165, 48000, 2), true))
        // A low data-saving stream still says so.
        assertEquals("Standard quality", qualityStatusLabel(NerdStats.Source("YouTube · InnerTube", kbps = 48), AudioFormatInfo("OPUS", 48, 48000, 2), false))
        assertEquals("Lossless · 16-bit/44.1 kHz", qualityStatusLabel(NerdStats.Source("Lossless Bridge"), AudioFormatInfo("FLAC", null, 44100, 2, 16), false))
    }
}
