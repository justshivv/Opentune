package com.opentune.data.subsonic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubsonicTest {
    private val server = Subsonic.Server("https://music.example.com/navidrome", "alice", "tok", "salt", null)

    @Test
    fun tokenMatchesTheApiDocsExample() {
        // subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", Subsonic.md5("sesame" + "c19b2d"))
    }

    @Test
    fun requestUrlKeepsTheServerPathAndSignsIn() {
        val url = Subsonic.url(server, "stream", "id" to "42", "format" to "raw")
        assertEquals("/navidrome/rest/stream", url.encodedPath)
        assertEquals("alice", url.queryParameter("u"))
        assertEquals("tok", url.queryParameter("t"))
        assertEquals("salt", url.queryParameter("s"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("raw", url.queryParameter("format"))
        assertNull("the password is never sent", url.queryParameter("p"))
    }

    @Test
    fun addressWithoutSchemeTriesHttpsThenHttp() {
        assertEquals(listOf("https://192.168.1.10:4533", "http://192.168.1.10:4533"), Subsonic.baseUrls(" 192.168.1.10:4533/ "))
        assertEquals(listOf("http://nas.local:4040"), Subsonic.baseUrls("http://nas.local:4040"))
        assertTrue(Subsonic.baseUrls("  ").isEmpty())
    }

    @Test
    fun failedResponsesSayWhy() {
        try {
            Subsonic.parse("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
            fail("expected an error")
        } catch (e: java.io.IOException) {
            assertEquals("Wrong username or password", e.message)
        }
        assertEquals("ok", Subsonic.parse("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome"}}""")["status"].toString().trim('"'))
    }

    @Test
    fun songsCarryNoCredentials() {
        val song = Subsonic.song(Json.parseToJsonElement("""{"id":"tr-1","title":"Song","artist":"Band","album":"LP","coverArt":"al-9","duration":245}"""))
        assertEquals("subsonic:tr-1", song.videoId)
        assertEquals("subsonic://cover/al-9", song.thumbnailUrl)
        assertEquals("4:05", song.durationText)
        assertEquals("LP", song.albumName)
        assertTrue(Subsonic.isSubsonic(song.videoId))
        assertTrue(Subsonic.isCoverRef(song.thumbnailUrl))
        assertFalse(Subsonic.isCoverRef("https://lh3.googleusercontent.com/x"))
    }
}
