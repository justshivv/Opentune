package com.opentune.data.covers

import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumCoversTest {
    /** A real MusicBrainz answer for "Blinding Lights" by The Weeknd. */
    private val response = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("musicbrainz-blinding-lights.json")).readText(),
    ).jsonObject

    @Test
    fun officialSingleComesBeforeCompilations() {
        val groups = AlbumCovers.releaseGroups(response, "The Weeknd")
        // The "Blinding Lights" single's release group.
        assertEquals("9b905cac-df8d-4942-bc4e-4fe0f2ed635f", groups.first())
    }

    @Test
    fun anotherArtistMatchesNothing() {
        assertTrue(AlbumCovers.releaseGroups(response, "Rick Astley").isEmpty())
    }

    @Test
    fun everyTrackButARadioStationIsLookedUp() {
        assertTrue(AlbumCovers.wants(Song("a", "t", "x", "https://i.ytimg.com/vi/a/hqdefault.jpg")))
        assertTrue(AlbumCovers.wants(Song("local:1", "t", "x", null)))
        // MediaStore's address for a local file's art, which may hold nothing.
        assertTrue(AlbumCovers.wants(Song("local:1", "t", "x", "content://media/external/audio/albumart/7")))
        // YouTube Music's own covers are smaller than Apple's masters, so they're upgraded too.
        assertTrue(AlbumCovers.wants(Song("a", "t", "x", "https://lh3.googleusercontent.com/abc=w544-h544")))
        assertFalse(AlbumCovers.wants(Song("radio:abc", "Station", "", null)))
    }

    private fun deezer(rows: String) = Json.parseToJsonElement("""{"data":[$rows]}""").jsonObject
    private fun row(title: String, artist: String, seconds: Int, album: String, cover: String) =
        """{"title":"$title","title_short":"$title","duration":$seconds,"artist":{"name":"$artist"},"album":{"title":"$album","cover_xl":"$cover"}}"""

    @Test
    fun deezerTakesOnlyTheSameRecording() {
        val response = deezer(listOf(
            row("Blinding Lights", "The Weeknd", 200, "After Hours", "https://cdn-images.dzcdn.net/images/cover/a/1000x1000-000000-80-0-0.jpg"),
            row("Blinding Lights", "The Weeknd", 262, "The Highlights", "https://cdn-images.dzcdn.net/images/cover/b/1000x1000-000000-80-0-0.jpg"),
        ).joinToString(","))
        assertEquals("https://cdn-images.dzcdn.net/images/cover/a/1000x1000-000000-80-0-0.jpg",
            AlbumCovers.deezerCover(response, "Blinding Lights", "The Weeknd", null, 200_400))
        // The extended mix's length matches only the compilation.
        assertEquals("https://cdn-images.dzcdn.net/images/cover/b/1000x1000-000000-80-0-0.jpg",
            AlbumCovers.deezerCover(response, "Blinding Lights", "The Weeknd", null, 262_000))
        // Another artist's song of the same name, or a named album Deezer hasn't got, is no match.
        assertEquals(null, AlbumCovers.deezerCover(response, "Blinding Lights", "Someone Else", null, 200_000))
        assertEquals(null, AlbumCovers.deezerCover(response, "Blinding Lights", "The Weeknd", "Live at SoFi Stadium", 200_000))
    }

    @Test(expected = java.io.IOException::class)
    fun aDeezerQuotaErrorIsNoAnswerRatherThanNoCover() {
        AlbumCovers.deezerCover(Json.parseToJsonElement("""{"error":{"code":4,"message":"Quota limit exceeded"}}""").jsonObject, "t", "a", null, 1_000)
    }
}
