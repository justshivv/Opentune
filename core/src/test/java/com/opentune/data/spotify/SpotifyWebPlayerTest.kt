package com.opentune.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyWebPlayerTest {
    private val known = listOf(107, 81, 49, 57, 67, 93, 87, 81, 69, 67, 40, 93, 48, 50, 46, 91, 94, 113, 41, 47)

    @Test
    fun totpMatchesAnIndependentImplementation() {
        // Worked out with Python's hmac, the same steps as the extension's totp().
        assertEquals("165105", SpotifyWebPlayer.totp(known, 1_700_000_000_000))
        assertEquals("758491", SpotifyWebPlayer.totp(known, 1_792_819_276_627))
    }

    @Test
    fun secretsAndHashesAreReadOutOfTheScript() {
        val script = """x={secret:'a\x41B\\\'',version:60},y={secret:"Z",version:61};"canvas","query","${"a".repeat(64)}""""
        val secrets = SpotifyWebPlayer.secretsIn(script)
        assertEquals(listOf(61, 60), secrets.map { it.version })
        assertEquals("aAB\\'".map { it.code }, secrets[1].codes)
        assertEquals("a".repeat(64), SpotifyWebPlayer.queryHashIn(script, "canvas"))
        assertNull(SpotifyWebPlayer.queryHashIn(script, "searchTracks"))
    }

    private fun hit(name: String, artist: String, album: String, ms: Long, id: String) =
        """{"item":{"data":{"__typename":"Track","uri":"spotify:track:$id","name":"$name","duration":{"totalMilliseconds":$ms},
        "artists":{"items":[{"profile":{"name":"$artist"}}]},"albumOfTrack":{"name":"$album"}}}}"""

    private fun search(vararg hits: String) = SpotifyWebPlayer.hitsIn(
        Json.parseToJsonElement("""{"data":{"searchV2":{"tracksV2":{"items":[${hits.joinToString(",")}]}}}}""").jsonObject,
    )

    @Test
    fun onlyTheSameRecordingIsTaken() {
        // As Spotify answered "Blinding Lights The Weeknd" on 2026-10-24.
        val hits = search(
            hit("City Of Blinding Lights", "U2", "How To Dismantle An Atomic Bomb", 345_706, "u2"),
            hit("Blinding Lights", "The Weeknd", "After Hours", 200_040, "single"),
            hit("Blinding Lights", "The Weeknd", "The Highlights", 200_040, "best"),
        )
        assertEquals("spotify:track:single", SpotifyWebPlayer.pick(hits, "Blinding Lights", "The Weeknd", null, 201_000)?.uri)
        assertEquals("spotify:track:best", SpotifyWebPlayer.pick(hits, "Blinding Lights", "The Weeknd", "The Highlights", 201_000)?.uri)
        assertNull(SpotifyWebPlayer.pick(hits, "Blinding Lights", "The Weeknd", null, 230_000))
        assertNull(SpotifyWebPlayer.pick(hits, "Blinding Lights", "Someone Else", null, 200_000))
        assertNull(SpotifyWebPlayer.pick(hits, "Save Your Tears", "The Weeknd", null, 200_000))
    }

    @Test
    fun aCanvasIsAnMp4OnSpotifysOwnCdn() {
        fun canvas(url: String?) = SpotifyWebPlayer.canvasIn(
            Json.parseToJsonElement("""{"data":{"trackUnion":{"canvas":${url?.let { "{\"url\":\"$it\"}" } ?: "null"}}}}""").jsonObject,
        )
        assertEquals("https://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4", canvas("https://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4"))
        assertNull(canvas(null))
        assertNull(canvas("http://canvaz.scdn.co/a.mp4"))
        assertNull(canvas("https://scdn.co.evil.example/a.mp4"))
        assertNull(canvas("https://canvaz.scdn.co/a.jpg"))
    }

    @Test
    fun theSongRadioIsAPlaylist() {
        // As Spotify answered for "Blinding Lights" on 2026-10-24.
        val answer = Json.parseToJsonElement("""{"total": 1,"mediaItems": [{"uri": "spotify:playlist:37i9dQZF1E8UXBoz02kGID"}]}""").jsonObject
        assertEquals("37i9dQZF1E8UXBoz02kGID", SpotifyWebPlayer.radioPlaylistIn(answer))
        assertNull(SpotifyWebPlayer.radioPlaylistIn(Json.parseToJsonElement("""{"total":0,"mediaItems":[]}""").jsonObject))
    }

    @Test
    fun withoutALengthTitleAndArtistDecide() {
        val hits = search(hit("Blinding Lights", "The Weeknd", "After Hours", 200_040, "single"))
        assertEquals("spotify:track:single", SpotifyWebPlayer.pick(hits, "Blinding Lights", "The Weeknd", null, 0)?.uri)
    }
}
