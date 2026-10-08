package com.opentune.data.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun readsSpotifysRecommendedSongsFromASongPage() {
        val picks = SpotifyRadio.parsePage(resource("spotify-track-husn.html"))
        assertEquals(5, picks.size)
        assertEquals("Pehle Bhi Main", picks[0].track.title)
        assertEquals(listOf("Vishal Mishra", "Raj Shekhar"), picks[0].track.artists)
        assertEquals("7yDHHVKLbvDmVw1XXhDDIO", picks[0].id)
        assertTrue(picks.all { it.id.length == 22 })
    }

    @Test fun bareTitlesDropWhatYouTubeAdds() {
        assertEquals("Kesariya", SpotifyRadio.bare("Kesariya (From \"Brahmastra\")"))
        assertEquals("Husn", SpotifyRadio.bare("Husn [Official Video]"))
        assertEquals("Excuses", SpotifyRadio.bare("Excuses - AP Dhillon | Gurinder Gill"))
        assertEquals("Blinding Lights", SpotifyRadio.bare("Blinding Lights"))
    }

    @Test fun aPageWithoutStateHasNoPicks() {
        assertEquals(emptyList<SpotifyRadio.Pick>(), SpotifyRadio.parsePage("<html><body>Not here</body></html>"))
    }

    @Test fun takesTheFirstSpotifyIdFromTheLookup() {
        val body = """[{"track_name":"Kesariya","spotify_track_ids":[]},{"track_name":"Husn","spotify_track_ids":["0TL0LFcwIBF5eX7arDIKxY","bad"]}]"""
        assertEquals("0TL0LFcwIBF5eX7arDIKxY", SpotifyRadio.firstId(body))
        assertNull(SpotifyRadio.firstId("""[{"spotify_track_ids":[]}]"""))
        assertNull(SpotifyRadio.firstId("<html>"))
    }

    @Test fun readsJioSaavnRecommendationsAndUnescapesNames() {
        val picks = JioSaavnRadio.parseReco(resource("jiosaavn-reco-husn.json"), "X0zxHHfs")
        assertEquals(6, picks.size)
        assertEquals("Let Her Go x Husn (Slowed & Reverb)", picks[0].track.title)
        assertEquals("Baarishein (Acoustic)", picks[2].track.title)
        assertEquals(listOf("Anuv Jain"), picks[2].track.artists)
        assertTrue(picks[2].track.durationMs > 0)
    }

    @Test fun findsTheSeedInJioSaavnSearch() {
        val results = JioSaavnRadio.parseSearch(resource("jiosaavn-search-husn.json"))
        assertEquals("X0zxHHfs", JioSaavnRadio.bestMatch(results, "Husn", "Anuv Jain"))
        assertNull(JioSaavnRadio.bestMatch(results, "Blinding Lights", "The Weeknd"))
    }

    @Test fun unescapesHtmlEntities() {
        assertEquals("Satranga (From \"ANIMAL\")", JioSaavnRadio.unescape("Satranga (From &quot;ANIMAL&quot;)"))
        assertEquals("Rock & Roll", JioSaavnRadio.unescape("Rock &amp; Roll"))
    }

    @Test fun usesTheFirstArtistForLookups() {
        assertEquals("Arijit Singh", Recommendations.mainArtist("Arijit Singh, Shreya Ghoshal"))
        assertEquals("Simon", Recommendations.mainArtist("Simon & Garfunkel"))
        assertEquals("The Weeknd", Recommendations.mainArtist("The Weeknd"))
    }
}
