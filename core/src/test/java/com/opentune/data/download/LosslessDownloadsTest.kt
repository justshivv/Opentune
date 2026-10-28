package com.opentune.data.download

import com.opentune.data.lossless.ExternalStreams
import org.junit.Assert.*
import org.junit.Test

class LosslessDownloadsTest {
    private val flac = ExternalStreams.Rendition("key", "https://cdn.example/file", "Source", true, 0,
        bits = 24, sampleRate = 96_000, addonId = "addon", codec = "flac", trackId = "track", tier = "HI_RES_LOSSLESS")

    @Test fun playingLosslessOverridesCompressedDownloadSettingAndSurvivesRestart() {
        assertTrue(LosslessDownloads.shouldTry(128, false, flac))
        assertTrue(LosslessDownloads.shouldTry(128, true, null))
        assertTrue(LosslessDownloads.shouldTry(Int.MAX_VALUE, false, null))
        assertFalse(LosslessDownloads.shouldTry(128, false, null))
        assertFalse(LosslessDownloads.shouldTry(128, false, flac.copy(lossless = false)))
    }

    @Test fun reusesExactQualityAndRefreshedLinkWithoutCatalogueLookup() {
        val stream = LosslessDownloads.direct(flac, { true }, { "https://cdn.example/fresh" })!!
        assertEquals("https://cdn.example/fresh", stream.url)
        assertEquals(24, stream.bits)
        assertEquals(96_000, stream.sampleRate)
        assertEquals("track", stream.trackId)
        assertEquals("flac", stream.extension)
    }

    @Test fun streamingOnlyDisabledUnknownAndSegmentedSourcesCannotBeCopiedDirectly() {
        assertNull(LosslessDownloads.direct(flac, { false }))
        assertNull(LosslessDownloads.direct(flac.copy(codec = null), { true }))
        assertNull(LosslessDownloads.direct(flac.copy(mimeType = "application/dash+xml"), { true }))
        assertNull(LosslessDownloads.direct(flac.copy(codec = "aac"), { true }))
        assertNull(LosslessDownloads.direct(flac.copy(addonId = null), { true }))
    }
}
