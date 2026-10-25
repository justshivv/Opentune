package com.opentune.data.reco

import com.opentune.data.model.Song
import com.opentune.data.settings.Recommender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecommendationSourceTest {
    private val spotify = listOf(Song("one", "First", "Artist", null), Song("two", "Second", "Artist", null))

    @Test fun shortSpotifyResultsKeepTheirSourceAndOrder() = runBlocking {
        val result = Recommendations.resolve(Recommender.SPOTIFY, { spotify }, { error("Must not fetch YouTube recommendations") })
        assertEquals(Recommender.SPOTIFY, result.engine)
        assertEquals(spotify, result.songs)
    }

    @Test fun emptySpotifyResultsDoNotSilentlySwitchProviders() = runBlocking {
        val result = Recommendations.resolve(Recommender.SPOTIFY, { emptyList() }, { error("Must not fetch YouTube recommendations") })
        assertEquals(Recommender.SPOTIFY, result.engine)
        assertTrue(result.songs.isEmpty())
    }

    @Test fun unavailableSpotifyDoesNotSwitchProviders() = runBlocking {
        val result = Recommendations.resolve(Recommender.SPOTIFY, { throw java.io.IOException("Unavailable") }, { error("Must not fetch YouTube recommendations") })
        assertEquals(Recommender.SPOTIFY, result.engine)
        assertTrue(result.songs.isEmpty())
    }

    @Test fun cancellingAnOldRecommendationRequestDoesNotStartFallback() = runBlocking {
        try {
            Recommendations.resolve(Recommender.SPOTIFY, { throw CancellationException("Source changed") }, { error("Cancelled request must not continue") })
            fail("Cancellation must reach the caller")
        } catch (_: CancellationException) { }
    }

    @Test fun otherProviderFallbackStillReportsItsActualSource() = runBlocking {
        val result = Recommendations.resolve(Recommender.JIOSAAVN, { emptyList() }, { spotify })
        assertEquals(Recommender.YOUTUBE, result.engine)
        assertEquals(spotify, result.songs)
    }
}
