package com.opentune.data.spotify

import android.app.Application
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpotifyAccountTest {
    @Test fun pkceChallengeMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyAccount.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun readsTracksAndSkipsEpisodes() {
        val track = Json.parseToJsonElement(
            """{"track":{"name":"Blinding Lights","type":"track","duration_ms":200040,"artists":[{"name":"The Weeknd"}],"album":{"name":"After Hours"}}}""",
        )
        assertEquals(Spotify.Track("Blinding Lights", listOf("The Weeknd"), "After Hours", 200_040), SpotifyAccount.track(track))
        assertNull(SpotifyAccount.track(Json.parseToJsonElement("""{"track":{"name":"Ep 1","type":"episode"}}""")))
        assertNull(SpotifyAccount.track(Json.parseToJsonElement("""{"track":null}""")))
    }

    @Test fun aPlaylistReadInFullIsNotCutOff() {
        val tracks = List(100) { Spotify.Track("Song $it", listOf("A"), null, 0) }
        val link = Spotify.Link(Spotify.Kind.PLAYLIST, "x")
        assertEquals(true, Spotify.Collection(link, "P", "", null, tracks).maybeTruncated)
        assertEquals(false, Spotify.Collection(link, "P", "", null, tracks, complete = true).maybeTruncated)
    }
}
