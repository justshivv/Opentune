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

    /** Issue 64: one song played on into AutoPlay; shuffle has no list to mix, so it mixes AutoPlay's songs. */
    @Test fun aSongPlayingIntoAutoPlayShufflesAutoPlaysSongs() {
        val tiers = mapOf("hand" to com.opentune.data.model.QueueTier.USER) +
            (1..8).associate { "auto$it" to com.opentune.data.model.QueueTier.AUTOPLAY }
        val upcoming = listOf("hand") + (1..8).map { "auto$it" }
        val movable = QueueShuffle.movable(upcoming) { tiers.getValue(it) }
        val (mixed, before) = QueueShuffle.mix(upcoming, movable, Random(7))
        assertNotEquals(upcoming, mixed)
        assertEquals("hand", mixed.first())
        assertEquals(upcoming, QueueShuffle.restore(mixed, movable, before) { it })
    }

    @Test fun aListToComeKeepsAutoPlayInPlace() {
        val tiers = (1..4).associate { "s$it" to com.opentune.data.model.QueueTier.CONTEXT } +
            mapOf("auto" to com.opentune.data.model.QueueTier.AUTOPLAY)
        val upcoming = (1..4).map { "s$it" } + "auto"
        val (mixed, _) = QueueShuffle.mix(upcoming, QueueShuffle.movable(upcoming) { tiers.getValue(it) }, Random(2))
        assertEquals("auto", mixed.last())
    }
}
