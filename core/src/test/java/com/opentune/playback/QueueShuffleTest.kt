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

    /** A queue entry with its tier, for AutoPlay arriving while shuffled. */
    private data class T(val id: String, val tier: com.opentune.data.model.QueueTier)

    private fun hand(id: String) = T(id, com.opentune.data.model.QueueTier.USER)
    private fun list(id: String) = T(id, com.opentune.data.model.QueueTier.CONTEXT)
    private fun auto(id: String) = T(id, com.opentune.data.model.QueueTier.AUTOPLAY)

    @Test fun arrivalsAreAutoPlaysNewSongsAtTheEnd() {
        val queue = listOf(list("s1"), auto("a1"), hand("h"), auto("a2"), auto("n1"), auto("n2"))
        val known = setOf("s1", "a1", "h", "a2")
        assertEquals(2, QueueShuffle.arrivals(queue, known, { it.tier == com.opentune.data.model.QueueTier.AUTOPLAY }) { it.id })
        assertEquals(0, QueueShuffle.arrivals(queue, known + "n1" + "n2", { it.tier == com.opentune.data.model.QueueTier.AUTOPLAY }) { it.id })
        // A song queued by hand at the end isn't AutoPlay's.
        assertEquals(0, QueueShuffle.arrivals(queue + hand("h2"), known, { it.tier == com.opentune.data.model.QueueTier.AUTOPLAY }) { it.id })
    }

    /** AutoPlay's songs added after shuffle went on are shuffled in, not all played last in the order they came. */
    @Test fun autoPlaysNewSongsAreShuffledInAmongAutoPlays() {
        val old = listOf(hand("h"), list("s1"), list("s2")) + (1..6).map { auto("a$it") }
        val fresh = (1..10).map { auto("n$it") }
        val placed = QueueShuffle.placeAdded(old + fresh, fresh.size,
            staysAhead = { it.tier != com.opentune.data.model.QueueTier.AUTOPLAY }, random = Random(9))
        assertEquals((old + fresh).map { it.id }.sorted(), placed.map { it.id }.sorted())
        // The next song, the song queued by hand and the list's songs keep their places.
        assertEquals(old.take(3), placed.take(3))
        // AutoPlay's songs already there keep their order between the new ones.
        assertEquals(old.drop(3), placed.filter { it.id.startsWith("a") })
        // The new ones are mixed in: not all at the end, and not in the order they came.
        assertNotEquals(fresh, placed.takeLast(fresh.size))
        assertNotEquals(fresh, placed.filter { it.id.startsWith("n") })
    }

    @Test fun theNextSongIsNeverDisplacedByArrivals() {
        val old = (1..3).map { auto("a$it") }
        val fresh = (1..20).map { auto("n$it") }
        repeat(50) { seed ->
            val placed = QueueShuffle.placeAdded(old + fresh, fresh.size, staysAhead = { false }, random = Random(seed))
            assertEquals(old.first(), placed.first())
        }
    }

    @Test fun withNothingLeftToComeTheArrivalsAreShuffledAmongThemselves() {
        val fresh = (1..8).map { auto("n$it") }
        val placed = QueueShuffle.placeAdded(fresh, fresh.size, staysAhead = { false }, random = Random(1))
        assertNotEquals(fresh, placed)
        assertEquals(fresh.toSet(), placed.toSet())
    }

    /** Shuffle on, AutoPlay adds songs, shuffle off: everything back as it would have been unshuffled. */
    @Test fun offPutsAutoPlaysArrivalsBackInTheOrderTheyCame() {
        val tier = { t: T -> t.tier }
        val upcoming = listOf(hand("h")) + (1..5).map { list("s$it") } + (1..2).map { auto("a$it") }
        val movable = QueueShuffle.movable(upcoming, tier)
        val restorable = { t: T -> movable(t) || t.tier == com.opentune.data.model.QueueTier.AUTOPLAY }
        val (mixed, _) = QueueShuffle.mix(upcoming, movable, Random(4))
        var before = upcoming.filter(restorable)
        val fresh = (1..6).map { auto("n$it") }
        val placed = QueueShuffle.placeAdded(mixed + fresh, fresh.size,
            staysAhead = { it.tier != com.opentune.data.model.QueueTier.AUTOPLAY }, random = Random(4))
        before = before + fresh
        assertEquals(upcoming + fresh, QueueShuffle.restore(placed, restorable, before) { it.id })
    }

    /** Issue 64's case: one song into AutoPlay, shuffled; more AutoPlay arrives and is mixed with all of it. */
    @Test fun arrivalsMixWithAutoPlaysWholeShuffleWhenThereIsNoList() {
        val tier = { t: T -> t.tier }
        val upcoming = listOf(hand("h")) + (1..5).map { auto("a$it") }
        val movable = QueueShuffle.movable(upcoming, tier)
        val (mixed, _) = QueueShuffle.mix(upcoming, movable, Random(2))
        val before = upcoming.filter(movable)
        val fresh = (1..5).map { auto("n$it") }
        val placed = QueueShuffle.placeAdded(mixed + fresh, fresh.size, staysAhead = { !movable(it) }, random = Random(2))
        assertEquals(hand("h"), placed.first())
        assertEquals(upcoming + fresh, QueueShuffle.restore(placed, movable, before + fresh) { it.id })
    }
}
