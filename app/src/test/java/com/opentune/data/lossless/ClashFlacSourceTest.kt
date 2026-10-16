package com.opentune.data.lossless

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ClashFlacSourceTest {
    @Test fun matchesRecordingInsteadOfFirstSearchHit() {
        val identity = CatalogIdentity("Blinding Lights", "The Weeknd", "After Hours", 200_000)
        val items = JSONArray("""[
            {"asin":"1","title":"Blinding Lights (Live)","artist":"The Weeknd","album":"After Hours","duration_sec":200},
            {"asin":"2","title":"Blinding Lights","artist":"A cover band","album":"After Hours","duration_sec":200},
            {"asin":"3","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours","duration_sec":30},
            {"asin":"4","title":"Blinding Lights","artist":"The Weeknd","album":"After Hours","duration_sec":200}
        ]""")
        assertEquals("4", ClashFlacSource.match(items, identity))
    }
    @Test fun refusesLossyPreviewEncryptedAndWrongTrackResponses() {
        val response = JSONObject("""{"track_id":"4","format_id":27,"stream_url":"https://cdn.example/file"}""")
        assertEquals("https://cdn.example/file", ClashFlacSource.streamUrl(response, "4"))
        assertNull(ClashFlacSource.streamUrl(response, "5"))
        response.put("format_id", 5)
        assertNull(ClashFlacSource.streamUrl(response, "4"))
        response.put("format_id", 6).put("preview", true)
        assertNull(ClashFlacSource.streamUrl(response, "4"))
        response.put("preview", false).put("encrypted", true)
        assertNull(ClashFlacSource.streamUrl(response, "4"))
    }
    @Test fun serverConfigurationNeverAcceptsInlineCredentials() {
        assertEquals("https://music.example/clash", ClashFlacSource.baseUrl("https://music.example/clash/"))
        for (url in listOf("http://music.example", "https://user:password@music.example", "https://music.example?token=x", "https://music.example/#x")) {
            assertNull(ClashFlacSource.baseUrl(url))
        }
    }
}
