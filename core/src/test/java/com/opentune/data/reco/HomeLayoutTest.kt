package com.opentune.data.reco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private val personal = listOf(
        HomeLayout.Personal("mixes", 1.0), HomeLayout.Personal("genres", 0.9),
        HomeLayout.Personal("discover", 0.8), HomeLayout.Personal("spotify", 0.7),
    )
    private val shelves = (0 until 8).map { "s$it" }

    @Test fun mixRowsNeverComeOneAfterAnother() {
        for (seed in 0L until 50L) {
            val order = HomeLayout.arrange(personal, shelves, { 0.0 }, seed)
            assertEquals((personal.map { it.id } + shelves).toSet(), order.toSet())
            order.zipWithNext().forEach { (a, b) ->
                assertFalse("$a then $b at seed $seed", a in personal.map { it.id } && b in personal.map { it.id })
            }
        }
    }

    @Test fun rowsPlayedFromMoveUp() {
        val order = HomeLayout.arrange(personal, shelves, { if (it == "spotify" || it == "s7") 1.0 else 0.0 }, seed = 1)
        assertEquals("spotify", order.first())
        assertEquals("s7", order[1])
    }

    @Test fun eachDayShufflesRowsThatRankAlike() {
        val even = personal.map { HomeLayout.Personal(it.id, 1.0) }
        val orders = (0L until 20L).map { HomeLayout.arrange(even, shelves, { 0.0 }, it).filter { id -> !id.startsWith("s") || id == "spotify" } }.toSet()
        assertTrue("shuffles", orders.size > 1)
        // The same day and turn always gives the same order.
        assertEquals(HomeLayout.arrange(even, shelves, { 0.0 }, 7), HomeLayout.arrange(even, shelves, { 0.0 }, 7))
    }

    @Test fun withoutShelvesTheRowsStillAllShow() {
        assertEquals(4, HomeLayout.arrange(personal, emptyList(), { 0.0 }, 3).size)
    }

    @Test fun playsFadeOverTwoWeeks() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(4.0, HomeEngagement.decayed(4.0 to 0L, 0L), 1e-9)
        assertEquals(2.0, HomeEngagement.decayed(4.0 to 0L, 14 * day), 1e-9)
        assertTrue(HomeEngagement.weightOf(20.0) < 1.0)
        assertNotEquals(HomeEngagement.weightOf(1.0), HomeEngagement.weightOf(10.0))
    }

    private fun card(id: String) = com.opentune.data.model.ShelfItem("Title $id", "Playlist", null, null, "VL$id")
    private fun shelf(title: String, vararg ids: String) = com.opentune.data.model.HomeShelf(title, ids.map(::card))

    @Test fun cardsShownHigherUpAreLeftOffLaterShelves() {
        val out = HomeLayout.withoutRepeats(listOf(
            shelf("Featured on", "a", "b", "c", "d"),
            shelf("Featured on", "b", "a", "e", "f", "g"),
            shelf("Mostly the same", "a", "b", "c", "h"),
            shelf("Short", "x", "y"),
        ))
        assertEquals(listOf("a", "b", "c", "d"), out[0]!!.items.map { it.browseId!!.removePrefix("VL") })
        assertEquals(listOf("e", "f", "g"), out[1]!!.items.map { it.browseId!!.removePrefix("VL") })
        // Only one new card left: not shown.
        assertEquals(null, out[2])
        // Short from the start, but nothing repeated: kept.
        assertEquals(2, out[3]!!.items.size)
    }
}
