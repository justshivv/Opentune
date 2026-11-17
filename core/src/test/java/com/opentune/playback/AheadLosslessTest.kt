package com.opentune.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AheadLosslessTest {
    @Test fun onlySongsAheadOpenedOnYouTubeArePointedAtTheirFlac() {
        val ahead = listOf("a" to AheadOpened.YOUTUBE, "b" to AheadOpened.YOUTUBE)
        assertEquals(listOf(1), aheadToStartOnFlac("b", ahead))
        // Not opened yet: it picks the FLAC itself when it opens.
        assertEquals(emptyList<Int>(), aheadToStartOnFlac("a", listOf("a" to AheadOpened.NOT_YET)))
        // Already on an external stream: nothing to do.
        assertEquals(emptyList<Int>(), aheadToStartOnFlac("a", listOf("a" to AheadOpened.EXTERNAL)))
        assertEquals(emptyList<Int>(), aheadToStartOnFlac("c", ahead))
    }

    @Test fun theSameSongTwiceAheadIsPointedAtItsFlacWhereverItWasOpenedOnYouTube() {
        val ahead = listOf("a" to AheadOpened.YOUTUBE, "b" to AheadOpened.NOT_YET, "a" to AheadOpened.YOUTUBE)
        assertEquals(listOf(0, 2), aheadToStartOnFlac("a", ahead))
    }

    @Test fun aSongOpenedOnYouTubeIsSearchedAgainOnceAndNeverThePlayingOne() {
        assertTrue(lookAgainForFlac(playing = false, losslessAllowed = true, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = true, losslessAllowed = true, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = false, losslessAllowed = false, firstForThisOpening = true, searching = false))
        assertFalse(lookAgainForFlac(playing = false, losslessAllowed = true, firstForThisOpening = false, searching = false))
        assertFalse(lookAgainForFlac(playing = false, losslessAllowed = true, firstForThisOpening = true, searching = true))
    }

    @Test fun upgradeWaitsForAStartupChoiceThatHasNotBegunYet() = runBlocking {
        var choosing = true
        val waited = async { awaitStartupChoice({ choosing }, timeoutMs = 2_000, pollMs = 10) }
        delay(50)
        assertFalse(waited.isCompleted)
        choosing = false
        assertTrue(withTimeout(2_000) { waited.await() })
    }

    @Test fun upgradeGivesUpWhenTheStartupChoiceNeverComes() = runBlocking {
        assertFalse(awaitStartupChoice({ true }, timeoutMs = 100, pollMs = 10))
        assertTrue(awaitStartupChoice({ false }, timeoutMs = 100, pollMs = 10))
    }
}
