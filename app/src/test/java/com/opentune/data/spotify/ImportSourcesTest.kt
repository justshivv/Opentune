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

    @Test fun readsAnExportifyCsv() {
        val csv = "\uFEFF\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Release Date\",\"Duration (ms)\"\r\n" +
            "\"spotify:track:1\",\"Blinding Lights\",\"After Hours\",\"The Weeknd\",\"2020-03-20\",\"200040\"\r\n" +
            "\"spotify:track:2\",\"Stay (with Justin Bieber)\",\"F*CK LOVE 3\",\"The Kid LAROI,Justin Bieber\",\"2021-07-23\",\"141805\"\r\n" +
            "\"spotify:track:3\",\"Say \"\"Hello\"\"\",\"Album, with comma\",\"Someone\",\"2001\",\"\"\r\n"
        val tracks = PlaylistCsv.parse(csv)
        assertEquals(3, tracks.size)
        assertEquals(Spotify.Track("Blinding Lights", listOf("The Weeknd"), "After Hours", 200_040), tracks[0])
        assertEquals(listOf("The Kid LAROI", "Justin Bieber"), tracks[1].artists)
        assertEquals("Say \"Hello\"", tracks[2].title)
        assertEquals("Album, with comma", tracks[2].album)
        assertEquals(0L, tracks[2].durationMs)
    }

    @Test fun readsOtherExportersByHeader() {
        val csv = "Track name,Artist name,Album,Playlist name,Type,ISRC\nLevitating,Dua Lipa,Future Nostalgia,Mine,Playlist,GBAHT2000942\n"
        assertEquals(listOf(Spotify.Track("Levitating", listOf("Dua Lipa"), "Future Nostalgia", 0)), PlaylistCsv.parse(csv))
        assertTrue(PlaylistCsv.parse("a,b\n1,2\n").isEmpty())
    }
}
