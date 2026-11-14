package com.opentune.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourcePrefetchTest {
    @Test fun slowLossyLookupDoesNotDelayLosslessAndFailuresStayIndependent() = runBlocking {
        val lossyStarted = CompletableDeferred<Unit>()
        val losslessReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val work = launch { prefetchExternalSources(
            lossless = { losslessReady.complete(Unit) },
            lossy = { lossyStarted.complete(Unit); release.await(); throw java.io.IOException("Unavailable") }) }
        try {
            withTimeout(2_000) { lossyStarted.await(); losslessReady.await() }
            assertFalse(work.isCompleted)
            release.complete(Unit)
            withTimeout(2_000) { work.join() }
            assertFalse(work.isCancelled)
        } finally { work.cancelAndJoin() }
    }

    @Test fun prefetchYieldsToPlayingSongButIsPromotedWhenItBecomesCurrent() = runBlocking {
        var playing: String? = "current"
        var pending = true
        val started = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val work = launch {
            started.complete(Unit)
            awaitForegroundLookup("next", { playing }, { pending })
            ready.complete(Unit)
        }
        try {
            started.await(); yield()
            assertFalse(ready.isCompleted)
            playing = "next"
            withTimeout(2_000) { ready.await() }
            pending = false
            withTimeout(2_000) { awaitForegroundLookup("another", { playing }, { pending }) }
        } finally { work.cancelAndJoin() }
    }

    @Test fun cancellingPrefetchCancelsBothSourceWaiters() = runBlocking {
        val started = List(2) { CompletableDeferred<Unit>() }
        val stopped = List(2) { CompletableDeferred<Unit>() }
        suspend fun wait(i: Int) { try { started[i].complete(Unit); awaitCancellation() } finally { stopped[i].complete(Unit) } }
        val work = launch { prefetchExternalSources({ wait(0) }, { wait(1) }) }
        withTimeout(2_000) { started.forEach { it.await() } }
        work.cancelAndJoin()
        assertTrue(stopped.all { it.isCompleted })
    }
}
