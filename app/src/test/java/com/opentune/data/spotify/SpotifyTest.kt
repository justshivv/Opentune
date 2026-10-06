package com.opentune.data.spotify

import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTest {
    @Test fun readsLinksInEveryForm() {
        val playlist = Spotify.Link(Spotify.Kind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(playlist, Spotify.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"))
        assertEquals(playlist, Spotify.parse("Check this out https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(playlist, Spotify.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(Spotify.Link(Spotify.Kind.ALBUM, "4yP0hdKOZPNshxUOjY0cZj"), Spotify.parse("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"))
        assertEquals(Spotify.Link(Spotify.Kind.TRACK, "11hcBLPtbMp4aQI6zGQLub"), Spotify.parse("https://open.spotify.com/intl-pt-BR/track/11hcBLPtbMp4aQI6zGQLub"))
        assertNull(Spotify.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Spotify.parse("https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"))
    }

    @Test fun recognisesShortLinks() {
        assertTrue(Spotify.looksLikeSpotify("https://spotify.link/AbCdEf123"))
        assertFalse(Spotify.looksLikeSpotify("https://example.com/playlist"))
    }

    @Test fun readsARealEmbedPage() {
        val html = javaClass.classLoader!!.getResource("spotify-embed-playlist.html")!!.readText()
        val c = Spotify.parseEmbed(html, Spotify.Link(Spotify.Kind.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M"))!!
        assertEquals("Today’s Top Hits", c.name)
        assertEquals("Spotify", c.subtitle)
        assertTrue(c.imageUrl!!.startsWith("https://i.scdn.co/image/"))
        assertEquals(50, c.tracks.size)
        assertEquals(Spotify.Track("Patient Zero", listOf("Taylor Swift"), null, 225_868), c.tracks.first())
        assertEquals(listOf("KAROL G", "Judeline", "rusowsky"), c.tracks[4].artists)
        assertFalse(c.maybeTruncated)
    }

    @Test fun anythingElseIsNotACollection() {
        assertNull(Spotify.parseEmbed("<html>Page not found</html>", Spotify.Link(Spotify.Kind.PLAYLIST, "x")))
    }

    @Test fun matchesTitleArtistAndLength() {
        val want = Spotify.Track("Blinding Lights", listOf("The Weeknd"), "After Hours", 200_040)
        val cover = Song("cover000001", "Blinding Lights", "Some Cover Band", null, durationText = "3:31")
        val real = Song("real0000001", "Blinding Lights", "The Weeknd", null, durationText = "3:20")
        val other = Song("other000001", "Save Your Tears", "The Weeknd", null, durationText = "3:35")
        assertEquals(real, SpotifyImport.best(want, listOf(cover, other, real)))
        assertNull(SpotifyImport.best(want, listOf(other)))
    }

    @Test fun ignoresRemasterAndFeatureTags() {
        assertEquals(SpotifyImport.key("Here Comes the Sun"), SpotifyImport.key("Here Comes The Sun - Remastered 2009"))
        assertEquals(SpotifyImport.key("Stay"), SpotifyImport.key("Stay (feat. Justin Bieber)"))
    }
}
