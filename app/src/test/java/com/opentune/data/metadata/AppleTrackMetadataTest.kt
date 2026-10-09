package com.opentune.data.metadata

import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AppleTrackMetadataTest {
    private val song = Song("video", "Uyi Amma", "Madhubanti Bagchi", null, albumName = "Azaad")
    private val entry = """{"kind":"song","trackId":123,"trackName":"Uyi Amma",
        "artistName":"Amit Trivedi, Madhubanti Bagchi & Amitabh Bhattacharya","collectionName":"Azaad (Original Motion Picture Soundtrack) - EP",
        "trackTimeMillis":253833,"releaseDate":"2025-01-04T12:00:00Z","primaryGenreName":"Bollywood",
        "trackExplicitness":"notExplicit","trackNumber":2,"discNumber":1,
        "trackViewUrl":"https://music.apple.com/in/album/azaad/122?i=123"}"""
    private fun select(rows: String = entry, wanted: Song = song, duration: Long = 253000) =
        AppleTrackMetadataRepository.select(Json.parseToJsonElement("""{"results":[$rows]}""").jsonObject, wanted, duration)

    @Test fun matchesCatalogWithoutInventingAudioQualityOrIsrc() {
        val match = select()!!
        assertEquals("Bollywood", match.genre)
        assertEquals("2025-01-04", match.releaseDate)
        assertEquals(2, match.trackNumber)
    }
    @Test fun rejectsWrongArtistAlbumDurationAndVersion() {
        assertNull(select(wanted = song.copy(artist = "Someone else")))
        assertNull(select(wanted = song.copy(albumName = "Another album")))
        assertNull(select(duration = 270000))
        assertNull(select(duration = 0))
        assertNull(select(entry.replace("\"Uyi Amma\"", "\"Uyi Amma (Remix)\"")))
        assertNull(select(entry.replace("\"song\"", "\"music-video\"")))
    }
    @Test fun rejectsAmbiguityButAcceptsRepeatedSameId() {
        assertNull(select("$entry," + entry.replace("\"trackId\":123", "\"trackId\":124")))
        assertNotNull(select("$entry,$entry"))
    }
    @Test fun rejectsUntrustedCatalogLinksAndMalformedRows() {
        assertNull(select(entry.replace("music.apple.com", "music.apple.com.evil.example")))
        assertNull(select(entry.replace("https://", "http://")))
        assertNull(select(entry.replace("253833", "-1")))
        assertNotNull(select("null,{},$entry"))
        assertNull(select(entry.replace("\"trackId\":123", "\"trackId\":{}")))
    }
    @Test fun ignoresMalformedOptionalFields() {
        val match = select(entry.replace("2025-01-04T12:00:00Z", "bad date").replace("\"trackNumber\":2", "\"trackNumber\":-2"))!!
        assertNull(match.releaseDate)
        assertNull(match.trackNumber)
    }
}
