package com.opentune.ui.home

import com.opentune.data.model.HomeShelf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshPicksTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val picks = FreshPicks(scope)
    private fun shelf(name: String) = HomeShelf(name, emptyList())

    @After fun stop() = scope.cancel()

    @Test fun oneRefreshShowsItsPicksAndClearsThePlaceholder() {
        val made = CompletableDeferred<List<HomeShelf>>()
        picks.refresh(made)
        assertTrue(picks.pending)
        assertNull(picks.shelf)
        made.complete(listOf(shelf("first")))
        assertFalse(picks.pending)
        assertEquals("first", picks.shelf?.title)
    }

    /** Pulled twice quickly: the first refresh finishing doesn't end the second's placeholder. */
    @Test fun anEarlierRefreshFinishingLeavesTheLatestPending() {
        val first = CompletableDeferred<List<HomeShelf>>()
        val second = CompletableDeferred<List<HomeShelf>>()
        picks.refresh(first)
        picks.refresh(second)
        first.complete(listOf(shelf("first")))
        assertTrue("the placeholder went before the latest picks were made", picks.pending)
        assertNull("the older refresh's picks were shown", picks.shelf)
        second.complete(listOf(shelf("second")))
        assertFalse(picks.pending)
        assertEquals("second", picks.shelf?.title)
    }

    @Test fun picksThatFailStillClearThePlaceholder() {
        val made = CompletableDeferred<List<HomeShelf>>()
        picks.refresh(made)
        made.complete(emptyList())
        assertFalse(picks.pending)
        assertNull(picks.shelf)
    }
}
