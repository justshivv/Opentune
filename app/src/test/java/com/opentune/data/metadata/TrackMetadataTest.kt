package com.opentune.data.metadata

import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TrackMetadataTest {
    private val song = Song("video", "Uyi Amma", "Madhubanti Bagchi", null, albumName = "Azaad")
    private val recording = """{"id":"12345678-1234-1234-1234-123456789012","title":"Uyi Amma","length":253833,
        "artist-credit":[{"name":"Madhubanti Bagchi"}],"isrcs":["INZ031417833"],
        "releases":[{"title":"Azaad","date":"2025-01-04"}]}"""
    private fun select(recordings: String, wanted: Song = song) = TrackMetadataRepository.select(
        Json.parseToJsonElement("""{"recordings":[$recordings]}""").jsonObject, wanted, 253000)
    @Test fun matchesRecordingAndRetainsRealIsrc() {
        assertEquals(listOf("INZ031417833"), select(recording)?.isrcs)
    }
    @Test fun rejectsAnotherRecordingEvenWhenSearchScoresItHighly() {
        assertNull(select(recording.replace("253833", "290000")))
        assertNull(select(recording, song.copy(title = "Uyi Amma (Remix)")))
        assertNull(select(recording, song.copy(artist = "Another artist")))
        assertNull(select(recording, song.copy(albumName = "Another album")))
    }
    @Test fun rejectsAmbiguousMatches() {
        assertNull(select(recording + "," + recording.replace("123456789012", "123456789013")))
    }
}
