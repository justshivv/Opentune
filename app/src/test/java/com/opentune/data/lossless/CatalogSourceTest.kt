package com.opentune.data.lossless

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CatalogSourceTest {
    private val identity = CatalogIdentity("Husn", "Anuv Jain", "Husn", 217_000)

    @Test fun doesNotSubstituteCoversRemixesOrDifferentAlbums() {
        assertTrue(identity.matches("HUSN", listOf("Anuv Jain"), "Husn", 218_000))
        assertFalse(identity.matches("Husn", listOf("Another Artist"), "Husn", 217_000))
        assertFalse(identity.matches("Husn (Live)", listOf("Anuv Jain"), "Husn", 217_000))
        assertFalse(identity.matches("Husn", listOf("Anuv Jain"), "Acoustic sessions", 217_000))
        assertFalse(identity.matches("Husn", listOf("Anuv Jain"), "Husn", 190_000))
        assertFalse(CatalogIdentity("", "", null, 217_000).matches("", listOf(""), null, 217_000))
    }

    @Test fun keepsNonLatinNamesDistinct() {
        assertNotEquals(CatalogIdentity.key("你好"), CatalogIdentity.key("再见"))
        assertNotEquals(CatalogIdentity.key("हुस्न"), "")
        assertNotEquals(CatalogIdentity.key("दिन"), CatalogIdentity.key("दीन"))
    }

    @Test fun qobuzChecksSeparateVersionFieldAndRejectsPreview() {
        val track = JSONObject("""{"id":123,"title":"Husn","duration":217,"performer":{"name":"Anuv Jain"},"album":{"title":"Husn"}}""")
        val root = JSONObject().put("success", true).put("data", JSONObject().put("tracks", JSONObject().put("items", org.json.JSONArray().put(track))))
        assertEquals("123", QobuzCatalog.match(root, identity))
        track.put("version", "Live")
        assertNull(QobuzCatalog.match(root, identity))
        assertNull(QobuzCatalog.streamUrl(JSONObject("""{"success":true,"previewDetected":true,"data":{"url":"https://cdn.example/song.flac"}}""")))
        assertNull(QobuzCatalog.streamUrl(JSONObject("""{"success":false,"data":{"url":"https://cdn.example/song.flac"}}""")))
    }

    @Test fun saavnRequiresCatalog320FlagAndIdentity() {
        val song = JSONObject("""{"id":"X0zxHHfs","title":"Husn","more_info":{"album":"Husn","duration":"217","320kbps":"true","artistMap":{"primary_artists":[{"name":"Anuv Jain"}]}}}""")
        val root = JSONObject().put("results", org.json.JSONArray().put(song))
        assertEquals("X0zxHHfs", JioSaavnSource.match(root, identity))
        song.getJSONObject("more_info").put("320kbps", "false")
        assertNull(JioSaavnSource.match(root, identity))
    }

    @Test fun saavnRenditionRewritePreservesQueriesAndRejectsUnknownOrigins() {
        assertEquals("https://aac.saavncdn.com/123/song_320.mp4?token=a", JioSaavnSource.rendition320("https://aac.saavncdn.com/123/song_96.mp4?token=a"))
        assertNull(JioSaavnSource.rendition320("https://aac.saavncdn.com/123/song.mp4"))
        assertNull(JioSaavnSource.rendition320("https://saavncdn.com.evil.example/song_96.mp4"))
        assertNull(JioSaavnSource.rendition320("http://aac.saavncdn.com/song_96.mp4"))
    }

    @Test fun saavnRejectsLowBitrateOrHtmlEvenWhenUrlSays320() {
        val header = ByteArray(64).apply { "ftyp".toByteArray().copyInto(this, 4) }
        assertTrue(JioSaavnSource.verified320(header, 9_142_812, 217_000))
        assertFalse(JioSaavnSource.verified320(header, 2_700_000, 217_000))
        assertFalse(JioSaavnSource.verified320(header, null, 217_000))
        assertFalse(JioSaavnSource.verified320(ByteArray(64), 9_142_812, 217_000))
    }
}
