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
        assertEquals("Qobuz · FLAC · 24-bit · 96 kHz", sourceQualityLabel(NerdStats.Source("Qobuz",24,96000), null))
        assertEquals("JioSaavn · AAC · 320 kbps", sourceQualityLabel(NerdStats.Source("JioSaavn",kbps=320), AudioFormatInfo("AAC",null,44100,2)))
    }
}
