package com.opentune.data.lossless

import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class JioSaavnLookupTest {
    // Minimal fields from the public Uyi Amma/Azaad catalog response.
    private fun track() = JSONObject("""{"id":"GHXYZHMU","title":"Uyi Amma","more_info":{"album":"Azaad","duration":"253","320kbps":"true","artistMap":{"primary_artists":[{"name":"Madhubanti Bagchi"}],"artists":[{"name":"Amit Trivedi","role":"music"},{"name":"Madhubanti Bagchi","role":"singer"},{"name":"Amitabh Bhattacharya","role":"lyricist"},{"name":"Rasha Thadani","role":"starring"}]}}}""")
    private fun search(track: JSONObject) = JSONObject().put("results", org.json.JSONArray().put(track))
    private val song = Song("abcdefghijk", "Uyi Amma", "Amit Trivedi", null, "4:13", albumName = "Azaad")

    @Test fun soundtrackMatchesComposerSingerOrCombinedCreditsButNotActorsOrUnrelatedArtists() {
        val root = search(track())
        for (credit in listOf("Amit Trivedi", "Madhubanti Bagchi", "Amit Trivedi, Madhubanti Bagchi & Amitabh Bhattacharya")) {
            assertEquals("GHXYZHMU", JioSaavnSource.match(root, CatalogIdentity("Uyi Amma", credit, "Azaad", 253_000)))
        }
        for (credit in listOf("Rasha Thadani", "Another Singer", "Amit Trivedi & Another Singer")) {
            assertNull(JioSaavnSource.match(root, CatalogIdentity("Uyi Amma", credit, "Azaad", 253_000)))
        }
        assertNull(JioSaavnSource.match(root, CatalogIdentity("Uyi Amma (Live)", song.artist, "Azaad", 253_000)))
        assertNull(JioSaavnSource.match(root, CatalogIdentity(song.title, song.artist, "Other album", 253_000)))
        assertNull(JioSaavnSource.match(root, CatalogIdentity(song.title, song.artist, "Azaad", 200_000)))
    }

    @Test fun completeLookupChecksDetailsAndAudioBytesBeforeReturning320() = runBlocking {
        val track = track()
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        }
        track.getJSONObject("more_info").put("encrypted_media_url",
            Base64.getEncoder().encodeToString(cipher.doFinal("https://aac.saavncdn.com/123/uyi_96.mp4".toByteArray())))
        val requests = mutableListOf<Request>()
        val messages = mutableListOf<String>()
        val result = JioSaavnSource.resolve(song, 253_000, report = { messages += it }) { request, limit, prefix ->
            requests += request
            when (request.url.queryParameter("__call")) {
                "search.getResults" -> {
                    assertEquals("Uyi Amma Azaad", request.url.queryParameter("q"))
                    data(search(track).toString())
                }
                "song.getDetails" -> {
                    assertEquals("GHXYZHMU", request.url.queryParameter("pids"))
                    data(JSONObject().put("GHXYZHMU", track).toString())
                }
                else -> {
                    assertTrue(prefix)
                    assertEquals(64, limit)
                    assertEquals("bytes=0-63", request.header("Range"))
                    LosslessHttp.Data(ByteArray(64).apply { "ftyp".toByteArray().copyInto(this, 4) }, 10_506_899)
                }
            }
        }
        assertEquals("https://aac.saavncdn.com/123/uyi_320.mp4", result)
        assertEquals(3, requests.size)
        assertEquals(listOf("JioSaavn: verified 320 kbps stream"), messages)
    }

    @Test fun catalogMissAndNetworkFailureHaveDifferentFallbackReasons() = runBlocking {
        val messages = mutableListOf<String>()
        assertNull(JioSaavnSource.resolve(song, 253_000, report = { messages += it }) { _, _, _ -> data("{\"results\":[]}") })
        assertTrue(messages.single().contains("no matching"))
        messages.clear()
        assertNull(JioSaavnSource.resolve(song, 253_000, report = { messages += it }) { _, _, _ -> throw java.io.IOException() })
        assertTrue(messages.single().contains("service unavailable"))
    }

    @Test fun albumSearchMissStillTriesArtistSearch() = runBlocking {
        val queries = mutableListOf<String?>()
        JioSaavnSource.resolve(song, 253_000) { request, _, _ ->
            queries += request.url.queryParameter("q")
            data("{\"results\":[]}")
        }
        assertEquals(listOf("Uyi Amma Azaad", "Uyi Amma Amit Trivedi"), queries)
    }

    private fun data(text: String) = LosslessHttp.Data(text.toByteArray(), null)
}
