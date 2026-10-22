package com.opentune.data.links

import com.opentune.data.metadata.Deezer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLinksTest {
    @Test fun knowsOtherServicesLinks() {
        assertEquals("https://music.apple.com/us/album/hello/1544494115?i=1544494392", MusicLinks.linkIn("Listen: https://music.apple.com/us/album/hello/1544494115?i=1544494392"))
        assertEquals("https://www.deezer.com/en/track/3135556", MusicLinks.linkIn("https://www.deezer.com/en/track/3135556"))
        assertEquals("https://music.amazon.co.uk/albums/B0", MusicLinks.linkIn("https://music.amazon.co.uk/albums/B0"))
        assertNull(MusicLinks.linkIn("https://music.youtube.com/watch?v=abc"))
        assertNull(MusicLinks.linkIn("no link here"))
    }

    @Test fun readsUniTunesAnswer() {
        val body = """{"entityUniqueId":"APPLEMUSIC::TRACK::1","entitiesByUniqueId":{"APPLEMUSIC::TRACK::1":{"title":"Hello","artistName":"Adele"}}}"""
        assertEquals(MusicLinks.Found("Hello", "Adele"), MusicLinks.parseUniTune(body))
        assertNull(MusicLinks.parseUniTune("""{"statusCode":404}"""))
    }

    @Test fun readsDeezerSearch() {
        val body = """{"data":[{"id":3135556,"title":"Harder, Better, Faster, Stronger","isrc":"GBDUW0000059","duration":226,"artist":{"name":"Daft Punk"},"album":{"cover_xl":"https://e/x.jpg"}}]}"""
        assertEquals(Deezer.Track(3135556, "Harder, Better, Faster, Stronger", "Daft Punk", 226, "GBDUW0000059", "https://e/x.jpg"), Deezer.parseSearch(body).single())
    }
}
