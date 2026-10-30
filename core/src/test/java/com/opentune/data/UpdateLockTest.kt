package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateLockTest {
    // The releases the site listed on 30 Oct 2026, newest first.
    private val published = listOf("1.0.3", "1.0.2", "1.0.1", "0.4.18", "0.4.17", "0.4.16", "0.4.15", "0.4.14", "0.4.13", "0.4.12")

    @Test fun countsTheReleasesNewerThanThisOne() {
        assertEquals(0, UpdateLock.behind(published, "1.0.3"))
        assertEquals(1, UpdateLock.behind(published, "1.0.2"))
        assertEquals(2, UpdateLock.behind(published, "1.0.1"))
        assertEquals(3, UpdateLock.behind(published, "0.4.18"))
        // Older than everything listed: at least as far behind as the list is long.
        assertEquals(10, UpdateLock.behind(published, "0.3.0"))
        // A build newer than anything published (a preview) is never behind.
        assertEquals(0, UpdateLock.behind(published, "1.1.0"))
    }

    @Test fun twoBehindIsStillAllowedThreeIsNot() {
        assert(UpdateLock.behind(published, "1.0.1") <= UpdateLock.MAX_BEHIND)
        assert(UpdateLock.behind(published, "0.4.18") > UpdateLock.MAX_BEHIND)
    }

    @Test fun aReleaseListedTwiceCountsOnce() {
        assertEquals(1, UpdateLock.behind(listOf("1.0.3", "1.0.3", "1.0.2"), "1.0.2"))
    }
}
