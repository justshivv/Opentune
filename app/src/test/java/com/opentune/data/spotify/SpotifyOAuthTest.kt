package com.opentune.data.spotify

import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SpotifyOAuthTest {
    @Test fun rejectsExpiredWrongDuplicateAndReplayedStates() {
        assertTrue(SpotifyOAuth.matchesAttempt(listOf("one"), "one", 1000, 2000))
        assertFalse(SpotifyOAuth.matchesAttempt(listOf("other"), "one", 1000, 2000))
        assertFalse(SpotifyOAuth.matchesAttempt(listOf("one", "one"), "one", 1000, 2000))
        assertFalse(SpotifyOAuth.matchesAttempt(listOf("one"), "one", 1000, 601001))
        assertFalse(SpotifyOAuth.matchesAttempt(listOf("one"), "", 0, 2000))
        assertFalse(SpotifyOAuth.matchesAttempt(listOf("one"), "one", 3000, 2000))
    }

    @Test fun paginationKeepsAllPagesAndRejectsForeignNextUrlBeforeReadingIt() = kotlinx.coroutines.runBlocking {
        val first = "https://api.spotify.com/v1/me/tracks?limit=50"
        val next = "https://api.spotify.com/v1/me/tracks?offset=50"
        val read = mutableListOf<String>()
        val rows = SpotifyLibrary.pages(first) { url ->
            read += url
            JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("id", url)))
                .put("next", if (url == first) next else JSONObject.NULL)
        }
        assertEquals(2, rows.size)
        assertEquals(listOf(first, next), read)
        var calls = 0
        try {
            SpotifyLibrary.pages(first) {
                calls++
                JSONObject().put("items", org.json.JSONArray()).put("next", "https://foreign.example/v1/me")
            }
            fail("Foreign next URL must be rejected")
        } catch (_: java.io.IOException) { assertEquals(1, calls) }
    }

    @Test fun pkceMatchesRfc7636TestVectorAndHasReadOnlyScopes() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyOAuth.challenge(verifier))
        val url = SpotifyOAuth.authorize("a".repeat(32), "state", verifier).toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertFalse(url.queryParameter("scope")!!.contains("modify"))
        assertNull(url.queryParameter("client_secret"))
        assertEquals(SpotifyOAuth.REDIRECT, url.queryParameter("redirect_uri"))
        assertNotEquals(SpotifyOAuth.randomValue(), SpotifyOAuth.randomValue())
    }
    @Test fun acceptsOnlyExactCallbackAndApiOrigins() {
        assertTrue(SpotifyAccount.isCallback(Uri.parse(SpotifyOAuth.REDIRECT + "?code=x&state=y")))
        assertTrue(SpotifyAccount.isCallback(Uri.parse("opentune://spotify-callback?code=x&state=y")))
        assertFalse(SpotifyAccount.isCallback(Uri.parse("opentune://spotify-callback/other?code=x")))
        assertFalse(SpotifyAccount.isCallback(Uri.parse("https://justshivv.github.io.evil/Opentune/spotify/callback/")))
        assertTrue(SpotifyLibrary.allowedUrl("https://api.spotify.com/v1/me/tracks?offset=50"))
        for (url in listOf("https://api.spotify.com.evil/v1/me", "http://api.spotify.com/v1/me", "https://token@api.spotify.com/v1/me", "https://api.spotify.com:444/v1/me")) {
            assertFalse(SpotifyLibrary.allowedUrl(url))
        }
    }
    @Test fun parsesBothPlaylistItemShapesWithoutImportingEpisodesOrLocalFiles() {
        val track = JSONObject("""{"type":"track","name":"Husn","duration_ms":217000,"artists":[{"name":"Anuv Jain"}],"album":{"name":"Husn"}}""")
        assertEquals("Husn", SpotifyLibrary.track(JSONObject().put("item", track))?.title)
        assertEquals(217000L, SpotifyLibrary.track(JSONObject().put("track", track))?.durationMs)
        assertNull(SpotifyLibrary.track(JSONObject().put("is_local", true).put("track", track)))
        track.put("type", "episode")
        assertNull(SpotifyLibrary.track(JSONObject().put("item", track)))
        assertNull(SpotifyLibrary.track(JSONObject()))
    }
}
