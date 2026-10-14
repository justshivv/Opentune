package com.opentune.data.lossless

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackCandidatesTest {
    @Test fun startsFallbackBeforeFlacFinishesAndKeepsItWhenFlacStalls() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        var cancelled = false
        val result = PlaybackCandidates.resolve<String, String>(200,
            lossless = { try { fallbackStarted.await(); awaitCancellation() } finally { cancelled = true } },
            fallback = { fallbackStarted.complete(Unit); "verified AAC" })
        assertEquals(null to "verified AAC", result)
        assertTrue(cancelled)
    }

    @Test fun verifiedFlacWinsAndCancelsStalledFallback() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        var cancelled = false
        val result = withTimeout(2_000) { PlaybackCandidates.resolve<String, String>(5_000,
            lossless = { fallbackStarted.await(); "verified FLAC" },
            fallback = { try { fallbackStarted.complete(Unit); awaitCancellation() } finally { cancelled = true } }) }
        assertEquals("verified FLAC", result.first)
        assertTrue(cancelled)
    }
}
