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
    fun onlyVideoFramesAndMissingArtAreReplaced() {
        assertTrue(AlbumCovers.wants(Song("a", "t", "x", "https://i.ytimg.com/vi/a/hqdefault.jpg")))
        assertTrue(AlbumCovers.wants(Song("local:1", "t", "x", null)))
        // MediaStore's address for a local file's art, which may hold nothing.
        assertTrue(AlbumCovers.wants(Song("local:1", "t", "x", "content://media/external/audio/albumart/7")))
        assertFalse(AlbumCovers.wants(Song("a", "t", "x", "https://lh3.googleusercontent.com/abc=w544-h544")))
    }
}
