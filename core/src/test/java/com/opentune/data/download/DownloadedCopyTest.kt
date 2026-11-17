package com.opentune.data.download

import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import com.opentune.data.settings.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedCopyTest {
    private val song = SongRef.of(Song("abcdefghijk", "Song", "Artist", null))
    private fun done(path: String, source: String? = null, bits: Int? = null, rate: Int? = null, codec: String? = null) =
        DownloadEntry(song, state = DownloadState.DONE, path = path, source = source, bits = bits, sampleRate = rate, codec = codec)

    @Test fun describesEachKindOfDownload() {
        assertEquals("FLAC · 24-bit / 96 kHz · Lossless Bridge",
            DownloadedCopy.describe(done("/d/x.flac", "Lossless Bridge", 24, 96_000, "flac")))
        assertEquals("FLAC · 16-bit / 44.1 kHz · Lossless Bridge",
            DownloadedCopy.describe(done("/d/x.flac", "Lossless Bridge", 16, 44_100)))
        assertEquals("AAC · JioSaavn · 320 kbps", DownloadedCopy.describe(done("/d/x.m4a", "JioSaavn · 320 kbps")))
        assertEquals("Opus · YouTube", DownloadedCopy.describe(done("/d/x.webm")))
        assertEquals("AAC · YouTube", DownloadedCopy.describe(done("/d/x.m4a")))
        // An add-on's ALAC shares the M4A box with AAC; its bit depth tells them apart.
        assertEquals("ALAC · 24-bit / 48 kHz · Some add-on", DownloadedCopy.describe(done("/d/x.m4a", "Some add-on", 24, 48_000)))
    }

    @Test fun onlyLosslessFormatsCountAsLossless() {
        assertTrue(DownloadedCopy.lossless(done("/d/x.flac")))
        assertTrue(DownloadedCopy.lossless(done("/d/x.m4a", bits = 16, rate = 44_100)))
        assertTrue(DownloadedCopy.lossless(done("/d/x.wav")))
        assertFalse(DownloadedCopy.lossless(done("/d/x.m4a", "JioSaavn · 320 kbps")))
        assertFalse(DownloadedCopy.lossless(done("/d/x.webm")))
        assertFalse(DownloadedCopy.lossless(done("/d/x.mp3")))
    }

    @Test fun downloadedSongPlaysItsFileAsChosen() {
        fun file(choice: DownloadedPlayback, online: Boolean, metered: Boolean, onlyIfBetter: Boolean = false, streamBetter: Boolean = true) =
            DownloadedCopy.playsFile(choice, online, metered, onlyIfBetter, streamBetter)
        // Offline the file always plays.
        DownloadedPlayback.entries.forEach { assertTrue(file(it, online = false, metered = false)) }
        assertTrue(file(DownloadedPlayback.FILE, online = true, metered = false))
        assertFalse(file(DownloadedPlayback.WIFI, online = true, metered = false))
        assertTrue(file(DownloadedPlayback.WIFI, online = true, metered = true))
        assertFalse(file(DownloadedPlayback.ONLINE, online = true, metered = true))
        // "Prefer the download when it's as good": a file no stream beats plays, one a stream beats streams as chosen.
        assertTrue(file(DownloadedPlayback.ONLINE, online = true, metered = false, onlyIfBetter = true, streamBetter = false))
        assertFalse(file(DownloadedPlayback.ONLINE, online = true, metered = false, onlyIfBetter = true, streamBetter = true))
        assertTrue(file(DownloadedPlayback.WIFI, online = true, metered = true, onlyIfBetter = true, streamBetter = true))
        // Turned off, the stream plays even when it's no better.
        assertFalse(file(DownloadedPlayback.ONLINE, online = true, metered = false, onlyIfBetter = false, streamBetter = false))
    }

    @Test fun fileQualityIsMeasuredAgainstTheBestStream() {
        // 2:00 of Opus at about 130 kbps.
        val opus = done("/d/x.webm").copy(bytes = 1_950_000)
        assertEquals(130, DownloadedCopy.fileKbps(opus, 120_000))
        assertEquals(1411, DownloadedCopy.fileKbps(done("/d/x.flac", bits = 16, rate = 44_100), 120_000))
        assertEquals(4608, DownloadedCopy.fileKbps(done("/d/x.flac", bits = 24, rate = 96_000), 120_000))
        assertEquals(1411, DownloadedCopy.fileKbps(done("/d/x.flac"), 0))
        assertEquals(null, DownloadedCopy.fileKbps(done("/d/x.webm"), 120_000))
        // YouTube's best is no better than its own download; a FLAC is.
        assertFalse(DownloadedCopy.streamBeatsFile(130, DownloadedCopy.YOUTUBE_KBPS))
        assertTrue(DownloadedCopy.streamBeatsFile(130, DownloadedCopy.CD_KBPS))
        assertTrue(DownloadedCopy.streamBeatsFile(64, DownloadedCopy.YOUTUBE_KBPS))
        // A CD-quality file against a CD-quality stream: the file. Against a hi-res one: the stream.
        assertFalse(DownloadedCopy.streamBeatsFile(1411, DownloadedCopy.CD_KBPS))
        assertTrue(DownloadedCopy.streamBeatsFile(1411, DownloadedCopy.losslessKbps(24, 96_000)))
        // A file whose quality can't be told streams, as before.
        assertTrue(DownloadedCopy.streamBeatsFile(null, DownloadedCopy.YOUTUBE_KBPS))
        assertEquals(98_000L, DownloadedCopy.durationMs("1:38"))
        assertEquals(3_723_000L, DownloadedCopy.durationMs("1:02:03"))
    }

    @Test fun choiceKeepsTheEarlierSwitch() {
        // Defaults stay as before: stream whenever online.
        assertEquals(DownloadedPlayback.ONLINE, PlaybackSettings().downloadedPlayback)
        // "Stream downloads when online" turned off reads as "Always from the file".
        assertEquals(DownloadedPlayback.FILE, PlaybackSettings(streamDownloadsOnline = false).downloadedPlayback)
        DownloadedPlayback.entries.forEach { assertEquals(it, PlaybackSettings().withDownloadedPlayback(it).downloadedPlayback) }
        assertTrue(PlaybackSettings().streamDownloadsOnlyIfBetter)
    }

    @Test fun aFlacMissHoldsForAWeekWithTheSameAddons() {
        val day = 24L * 60 * 60 * 1000
        val miss = LosslessMiss(at = 10 * day, addons = "bridge")
        assertTrue(DownloadedCopy.missHolds(miss, 10 * day, "bridge"))
        assertTrue(DownloadedCopy.missHolds(miss, 16 * day, "bridge"))
        assertFalse("a week on, catalogues may have it", DownloadedCopy.missHolds(miss, 17 * day, "bridge"))
        assertFalse("another add-on may have it", DownloadedCopy.missHolds(miss, 11 * day, "bridge,other"))
        assertFalse("a clock set back doesn't stretch it", DownloadedCopy.missHolds(miss, 9 * day, "bridge"))
        assertFalse(DownloadedCopy.missHolds(null, 10 * day, "bridge"))
    }

    @Test fun losslessRedownloadOfferedOnlyWhereItHelps() {
        val youtube = done("/d/x.webm")
        assertTrue(DownloadedCopy.canRedownloadLossless(youtube, addonCanDownload = true))
        assertFalse(DownloadedCopy.canRedownloadLossless(youtube, addonCanDownload = false))
        assertFalse(DownloadedCopy.canRedownloadLossless(done("/d/x.flac", bits = 16), addonCanDownload = true))
        assertFalse(DownloadedCopy.canRedownloadLossless(youtube.copy(upgrade = DownloadUpgrade("w")), addonCanDownload = true))
        assertFalse(DownloadedCopy.canRedownloadLossless(youtube.copy(state = DownloadState.DOWNLOADING), addonCanDownload = true))
    }
}
