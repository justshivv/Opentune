package com.opentune.ui.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library search reads the phone's own songs once and keeps them. A read called off by the next
 * letter typed used to be kept as "no songs", so local files never showed in a search again.
 */
class LibrarySearchReadTest {
    @Test fun aReadCalledOffIsNotKeptAsEmpty() = runBlocking {
        var kept: List<String>? = null
        val started = CompletableDeferred<Unit>()
        val search = launch {
            readOrNull<List<String>> { started.complete(Unit); awaitCancellation() }?.also { kept = it }
        }
        started.await()
        search.cancel()
        search.join()
        assertTrue(search.isCancelled)
        assertNull(kept)
    }

    @Test fun aFailedReadIsNullAndAGoodOneIsKept() = runBlocking {
        assertNull(readOrNull<List<String>> { error("no permission") })
        yield()
        assertEquals(listOf("song"), readOrNull { listOf("song") })
    }
}
