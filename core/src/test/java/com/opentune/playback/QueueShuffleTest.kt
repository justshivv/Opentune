package com.opentune.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QueueShuffleTest {
    /** A queue entry: its id, and whether it's the list's own song (not queued by hand, not AutoPlay's). */
    private data class Q(val id: String, val list: Boolean = true)

    private fun ids(q: List<Q>) = q.map { it.id }

    @Test fun mixesTheListsSongsAndLeavesTheRestWhereTheyAre() {
        val hand = Q("hand", list = false)
        val auto = Q("auto", list = false)
        val upcoming = listOf(hand) + (1..8).map { Q("s$it") } + auto
        val (mixed, before) = QueueShuffle.mix(upcoming, { it.list }, Random(7))
        assertEquals(hand, mixed.first())
        assertEquals(auto, mixed.last())
        assertEquals(ids(upcoming).sorted(), ids(mixed).sorted())
        assertNotEquals(ids(upcoming), ids(mixed))
        assertEquals((1..8).map { "s$it" }, ids(before))
    }

    @Test fun offPutsTheOrderBack() {
        val upcoming = (1..10).map { Q("s$it") }
        val (mixed, before) = QueueShuffle.mix(upcoming, { it.list }, Random(3))
        assertEquals(upcoming, QueueShuffle.restore(mixed, { it.list }, before) { it.id })
    }

    @Test fun offKeepsChangesMadeWhileShuffled() {
        val upcoming = (1..6).map { Q("s$it") }
        val (mixed, before) = QueueShuffle.mix(upcoming, { it.list }, Random(11))
        // While shuffled: the next song played and left, one was removed, a new one was added.
        val now = mixed.drop(1).filterNot { it.id == "s4" } + Q("new")
        val restored = ids(QueueShuffle.restore(now, { it.list }, before) { it.id })
        assertEquals(ids(upcoming).filter { it in ids(now) } + "new", restored)
    }

    @Test fun aSongInTheListTwiceGoesBackToBothPlaces() {
        val upcoming = listOf(Q("a"), Q("b"), Q("a"), Q("c"))
        val (mixed, before) = QueueShuffle.mix(upcoming, { it.list }, Random(5))
        assertEquals(ids(upcoming), ids(QueueShuffle.restore(mixed, { it.list }, before) { it.id }))
    }

    @Test fun oneSongHasNothingToShuffle() {
        val upcoming = listOf(Q("only"), Q("hand", list = false))
        assertEquals(upcoming, QueueShuffle.mix(upcoming, { it.list }).first)
    }
}
