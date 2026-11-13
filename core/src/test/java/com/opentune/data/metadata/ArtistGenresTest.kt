package com.opentune.data.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistGenresTest {
    private fun response(vararg rows: Pair<String, String>) = Json.parseToJsonElement(
        """{"results":[${rows.joinToString(",") { (name, genre) -> """{"wrapperType":"artist","artistName":"$name","primaryGenreName":"$genre"}""" }}]}""",
    ).jsonObject

    @Test fun takesTheExactNameOnly() {
        val r = response("Diljit Dosanjh & Friends" to "Pop", "Diljit Dosanjh" to "Punjabi Pop")
        assertEquals("Punjabi Pop", ArtistGenres.pick(r, "diljit  dosanjh"))
        assertNull(ArtistGenres.pick(response("Arijit Singh Live" to "Pop"), "Arijit Singh"))
    }

    @Test fun genresReadAsMixNames() {
        assertEquals("Hip-Hop", ArtistGenres.pick(response("Divine" to "Hip-Hop/Rap"), "DIVINE"))
        assertNull(ArtistGenres.pick(response("Someone" to "Music"), "Someone"))
    }

    @Test fun accentsAndPunctuationDontMakeAnotherArtist() {
        assertEquals(ArtistGenres.key("Beyoncé"), ArtistGenres.key("beyonce"))
        assertEquals(ArtistGenres.key("A.R. Rahman"), ArtistGenres.key("AR Rahman"))
    }
}
