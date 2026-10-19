package com.opentune.data.lossless

import com.opentune.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class DownloadMatchTest {
    private val song = Song("abcdefghijk", "Wo Noor", "AP Dhillon", null, "3:10")
    private val track = LosslessTrack("B012345678", "Wo Noor", "AP Dhillon", durationMs = 190000)
    @Test fun matchesRecordingAndLinksOfflineCopyToOriginalSong() {
        val result = matchingDownloads(song, listOf(track, track))
        assertEquals(1, result.size)
        assertEquals(setOf(song.videoId), result.single().sourceIds)
    }
    @Test fun rejectsRemixesWrongArtistWrongDurationAndUnknownDuration() {
        assertTrue(matchingDownloads(song, listOf(track.copy(title = "Wo Noor (Remix)"),
            track.copy(artist = "Another artist"), track.copy(durationMs = 230000), track.copy(durationMs = 0))).isEmpty())
        assertTrue(matchingDownloads(song.copy(durationText = null), listOf(track)).isEmpty())
    }
    @Test fun normalizesPunctuationButPreservesAllCreditedArtists() {
        assertEquals(1, matchingDownloads(song.copy(title = "WO NOOR!", artist = "AP Dhillon & Shinda Kahlon"),
            listOf(track.copy(artist = "AP Dhillon, Shinda Kahlon"))).size)
        assertTrue(matchingDownloads(song.copy(artist = "AP Dhillon & Shinda Kahlon"), listOf(track)).isEmpty())
    }
}
