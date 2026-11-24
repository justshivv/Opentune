package com.opentune.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lists on the Downloads page: downloaded whole, or more than five songs downloaded one by one from them. */
class DownloadedListsTest {
    @After fun clear() { listOf("PL1", "PL2").forEach(DownloadedLists::remove) }

    @Test fun songsPickedFromAListGatherUnderIt() {
        val order = listOf("a", "b", "c", "d", "e", "f", "g")
        DownloadedLists.noteSongs("PL1", "Road trip", "", "https://img/cover.jpg", listOf("c"), order)
        DownloadedLists.noteSongs("PL1", "Road trip", "", "https://img/cover.jpg", listOf("a", "f"), order)
        val saved = DownloadedLists.get("PL1")!!
        assertFalse(saved.whole)
        assertEquals("Road trip", saved.title)
        assertEquals("https://img/cover.jpg", saved.thumbnailUrl)
        // In the playlist's own order, each once.
        assertEquals(listOf("a", "c", "f"), saved.songIds)
    }

    @Test fun aPickedListShowsPastFiveAWholeOneAtOnce() {
        DownloadedLists.noteSongs("PL1", "Picked", "", null, listOf("a"))
        val picked = DownloadedLists.get("PL1")!!
        assertFalse(DownloadedLists.shows(picked, 5))
        assertTrue(DownloadedLists.shows(picked, 6))
        DownloadedLists.save("PL2", "Whole", "", null, listOf("a", "b"))
        val whole = DownloadedLists.get("PL2")!!
        assertTrue(DownloadedLists.shows(whole, 1))
        assertFalse(DownloadedLists.shows(whole, 0))
    }

    @Test fun aListKeptWholeStaysWholeWhenASongIsPicked() {
        DownloadedLists.save("PL2", "Whole", "", null, listOf("a", "b"))
        DownloadedLists.noteSongs("PL2", "Whole", "", null, listOf("c"), listOf("a", "b", "c"))
        val saved = DownloadedLists.get("PL2")!!
        assertTrue(saved.whole)
        assertEquals(listOf("a", "b", "c"), saved.songIds)
    }

    @Test fun songsOutsideTheKnownOrderComeAfter() {
        assertEquals(listOf("b", "a", "x"), DownloadedLists.merge(listOf("x"), listOf("a", "b"), listOf("b", "a")))
        assertEquals(listOf("x", "a"), DownloadedLists.merge(listOf("x"), listOf("a", "x"), emptyList()))
    }
}
