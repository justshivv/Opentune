package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreFeedTest {
    @Test fun weavesListsInTurn() {
        assertEquals(listOf(1, "a", 2, "b", 3, 4), ExploreFeed.weave(listOf(listOf(1, 2, 3, 4), listOf("a", "b"), emptyList())))
    }
}
