package com.opentune.data.addons

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonProtocolTest {
    private val base = "https://music.example.com"

    private val manifest = """
        {"opentune": 1, "id": "river-label", "name": "River Label", "version": "1.2.0",
         "catalogue": "Records released by River Label, shared with the artists' permission.",
         "homepage": "https://river.example.com", "extra": [1, 2]}
    """

    @Test fun aManifestIsRead() {
        val addon = AddonProtocol.parseManifest(manifest, base)
        assertEquals("river-label", addon.id)
        assertEquals("River Label", addon.name)
        assertEquals("1.2.0", addon.version)
        assertEquals(base, addon.baseUrl)
        assertEquals("https://river.example.com", addon.homepage)
        assertTrue(addon.enabled)
    }

    @Test fun aManifestWithoutTheRequiredFieldsIsRefused() {
        assertThrows(IOException::class.java) { AddonProtocol.parseManifest("""{"id":"a","name":"A","catalogue":"x"}""", base) }
        assertThrows(IOException::class.java) { AddonProtocol.parseManifest("""{"opentune":1,"id":"has space","name":"A","catalogue":"x"}""", base) }
        assertThrows(IOException::class.java) { AddonProtocol.parseManifest("""{"opentune":1,"id":"a","name":"A"}""", base) }
        assertThrows(IOException::class.java) { AddonProtocol.parseManifest("""{"opentune":2,"id":"a","name":"A","catalogue":"x"}""", base) }
        assertThrows(IOException::class.java) { AddonProtocol.parseManifest("not json", base) }
    }

    @Test fun searchRowsWithoutAnIdOrTitleAreSkipped() {
        val tracks = AddonProtocol.parseSearch(
            """{"tracks":[
                {"id":"t1","title":"Paper Boats","artist":"River Choir","album":"Lanterns","duration":201,"artwork":"https://img.example.com/1.jpg"},
                {"id":"t2","title":"","artist":"x"},
                {"title":"No id"},
                {"id":"t3","title":"Plain","artwork":"http://img.example.com/3.jpg"}
            ]}""",
        )
        assertEquals(listOf("t1", "t3"), tracks.map { it.id })
        assertEquals(201L, tracks[0].durationSeconds)
        // Artwork has to be https.
        assertNull(tracks[1].artwork)
        val song = tracks[0].toSong(AddonProtocol.parseManifest(manifest, base))
        assertEquals("addon:river-label:t1", song.videoId)
        assertEquals("3:21", song.durationText)
    }

    @Test fun songIdsSplitBackIntoAddonAndTrack() {
        assertEquals("river-label" to "a:b/c", AddonProtocol.splitSongId(AddonProtocol.songId("river-label", "a:b/c")))
        assertNull(AddonProtocol.splitSongId("dQw4w9WgXcQ"))
        assertNull(AddonProtocol.splitSongId("addon:nothing"))
    }

    @Test fun aStreamMustBeAPlainAudioFileOverHttps() {
        val ok = AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1.flac?sig=abc","mimeType":"audio/flac","sampleRate":44100,"bitDepth":16}""")
        assertEquals("audio/flac", ok.mimeType)
        assertEquals(16, ok.bitDepth)
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"http://cdn.example.com/t1.mp3"}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1/index.m3u8"}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1","mimeType":"application/dash+xml"}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1.mp4","mimeType":"video/mp4"}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1.m4a","encrypted":true}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"url":"https://cdn.example.com/t1.m4a","drm":{"scheme":"x"}}""") }
        assertThrows(IOException::class.java) { AddonProtocol.parseStream("""{"mimeType":"audio/flac"}""") }
    }

    @Test fun plainHttpIsOnlyForThisDeviceOrAHomeNetwork() {
        assertEquals("https://music.example.com", AddonProtocol.baseUrl("music.example.com/"))
        assertEquals("https://music.example.com/addon", AddonProtocol.baseUrl("https://music.example.com/addon/manifest.json"))
        assertEquals("http://192.168.1.20:8080", AddonProtocol.baseUrl("http://192.168.1.20:8080"))
        assertNull(AddonProtocol.baseUrl("http://music.example.com"))
        assertNull(AddonProtocol.baseUrl("ftp://music.example.com"))
        assertNull(AddonProtocol.baseUrl("  "))
        assertTrue(AddonProtocol.isLocalHost("172.20.0.4"))
        assertFalse(AddonProtocol.isLocalHost("172.32.0.4"))
        assertFalse(AddonProtocol.isLocalHost("8.8.8.8"))
    }

    @Test fun requestUrlsEscapeTheQueryAndTrackId() {
        assertEquals("https://music.example.com/addon/search?q=paper%20boats%20%26%20more&limit=20", AddonProtocol.searchUrl("https://music.example.com/addon", "paper boats & more", 20))
        assertEquals("https://music.example.com/stream/a%2Fb%3Fc", AddonProtocol.streamUrl(base, "a/b?c"))
    }

    @Test fun theAddedListSurvivesBeingSaved() {
        val list = listOf(AddonProtocol.parseManifest(manifest, base), Addon("b", "B", "https://b.example.com", "Mine", enabled = false))
        assertEquals(list, AddonProtocol.decodeList(AddonProtocol.encodeList(list)))
        assertEquals(emptyList<Addon>(), AddonProtocol.decodeList(null))
    }
}
