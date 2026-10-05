package com.opentune.data.spotify

import android.app.Application
import com.opentune.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpotifyTest {
    @Test fun pkceChallengeMatchesTheRfcExample() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Spotify.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun readsTracksAndSkipsEpisodes() {
        val track = Json.parseToJsonElement(
            """{"track":{"name":"Blinding Lights","type":"track","duration_ms":200040,"artists":[{"name":"The Weeknd"}],"album":{"name":"After Hours"}}}""",
        )
        val episode = Json.parseToJsonElement("""{"track":{"name":"Ep 1","type":"episode"}}""")
        val gone = Json.parseToJsonElement("""{"track":null}""")
        assertEquals(Spotify.Track("Blinding Lights", listOf("The Weeknd"), "After Hours", 200_040), Spotify.track(track))
        assertNull(Spotify.track(episode))
        assertNull(Spotify.track(gone))
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
