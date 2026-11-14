package com.opentune.data.download

import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmartDownloadsTest {
    private fun song(id: String) = Song(id, id, "Artist", null)

    @Test fun requiresOptInWifiEnoughBatteryAndStorage() {
        val ample = SMART_RESERVE_BYTES * 2
        assertTrue(smartDownloadAllowed(true, true, false, 40, 25, ample))
        assertFalse(smartDownloadAllowed(false, true, true, 100, 25, ample))
        assertFalse(smartDownloadAllowed(true, false, true, 100, 25, ample))
        assertFalse(smartDownloadAllowed(true, true, false, 20, 25, ample))
        assertFalse(smartDownloadAllowed(true, true, false, -1, 25, ample))
        assertFalse(smartDownloadAllowed(true, true, true, 100, 25, SMART_RESERVE_BYTES - 1))
        assertTrue(smartDownloadAllowed(true, true, true, 20, 25, ample))
    }

    @Test fun budgetDeduplicatesAndNeverTakesOwnershipOfManualDownloads() {
        val songs = (1..30).map { song("song-$it") }
        val manual = DownloadEntry(SongRef.of(songs.first()), state = DownloadState.DONE)
        val smart = DownloadEntry(SongRef.of(songs[1]), state = DownloadState.DONE, smartDownload = true)
        val selected = smartDownloadSelection(songs + songs, mapOf("song-1" to manual, "song-2" to smart), 10)
        assertEquals(10, selected.size)
        assertEquals("song-2", selected.first().videoId)
        assertFalse(selected.any { it.videoId == "song-1" })
        assertEquals(10, selected.distinctBy { it.videoId }.size)
    }

    @Test fun oldDownloadIndexesStayManual() {
        val existing = DownloadEntry(SongRef.of(song("manual")), state = DownloadState.DONE)
        val encoded = Json.encodeToString(DownloadEntry.serializer(), existing)
        assertFalse(encoded.contains("smartDownload"))
        assertFalse(Json.decodeFromString(DownloadEntry.serializer(), encoded).smartDownload)
    }

    @Test fun persistedLimitsAreClamped() {
        val songs = (1..600).map { song("song-$it") }
        assertEquals(500, smartDownloadSelection(songs, emptyMap(), Int.MAX_VALUE).size)
        assertEquals(10, smartDownloadSelection(songs, emptyMap(), -1).size)
    }
}
