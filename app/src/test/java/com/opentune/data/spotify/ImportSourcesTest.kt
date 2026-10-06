package com.opentune.data.spotify

import com.opentune.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSourcesTest {
    @Test fun readsYouTubePlaylistLinks() {
        assertEquals("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj", YouTubePlaylists.parse("https://music.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj&si=x1"))
        assertEquals("PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj", YouTubePlaylists.parse("https://www.youtube.com/watch?v=abc&list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj"))
        assertEquals("OLAK5uy_kNWGJvgWVqlt5LsFDL9Sdluly4M8TvGkM", YouTubePlaylists.parse("https://music.youtube.com/browse/VLOLAK5uy_kNWGJvgWVqlt5LsFDL9Sdluly4M8TvGkM"))
        assertNull(YouTubePlaylists.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubePlaylists.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    /** A 200-song playlist: 100 on the page, 100 on the continuation in the newer answer shape. */
    @Test fun readsBothPagesOfALongPlaylist() {
        fun fixture(name: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject
        val first = InnertubeParser.parsePlaylistShelf(fixture("ytm-playlist-page1.json"))!!
        assertEquals(100, first.songs.size)
        assertNotNull(first.continuation)
        val second = InnertubeParser.parsePlaylistContinuation(fixture("ytm-playlist-page2.json"))
        assertEquals(100, second.songs.size)
        assertTrue(second.songs.none { s -> first.songs.any { it.videoId == s.videoId } })
        assertNull(second.continuation)
    }
}
