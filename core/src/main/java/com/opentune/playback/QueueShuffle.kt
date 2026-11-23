package com.opentune.playback

import com.opentune.data.model.QueueTier
import kotlin.random.Random

/**
 * The orders behind the player's shuffle button ([PlayerConnection.toggleShuffle]), on the songs
 * after the current one. Only the list's own songs move ([isList]); songs queued by hand and
 * AutoPlay's keep their places. AutoPlay's songs that arrive while shuffle is on are mixed in
 * among AutoPlay's ([placeAdded]).
 */
internal object QueueShuffle {
    /**
     * Which of [items] shuffle moves: the list's own songs, or, when fewer than two of those are
     * still to come (a single song playing on into AutoPlay), AutoPlay's as well, so the button
     * always changes something. Songs queued by hand never move.
     */
    fun <T> movable(items: List<T>, tier: (T) -> QueueTier): (T) -> Boolean =
        if (items.count { tier(it) == QueueTier.CONTEXT } >= 2) { item -> tier(item) == QueueTier.CONTEXT }
        else { item -> tier(item) != QueueTier.USER }

    /** [items] with the list's songs in a new order, and those songs as they were, to put back later. */
    fun <T> mix(items: List<T>, isList: (T) -> Boolean, random: Random = Random): Pair<List<T>, List<T>> {
        val slots = items.indices.filter { isList(items[it]) }
        val before = slots.map { items[it] }
        if (before.size < 2) return items to before
        val mixed = before.shuffled(random).let { m -> if (m == before) before.asReversed() else m }
        return fill(items, slots, mixed) to before
    }

    /**
     * [items] with the list's songs back in the order [before] had them, matched by [id]; a song
     * there twice takes its places in turn, and songs that weren't there keep their order after them.
     */
    fun <T> restore(items: List<T>, isList: (T) -> Boolean, before: List<T>, id: (T) -> String): List<T> {
        val slots = items.indices.filter { isList(items[it]) }
        val places = HashMap<String, ArrayDeque<Int>>()
        before.forEachIndexed { i, item -> places.getOrPut(id(item)) { ArrayDeque() }.add(i) }
        val restored = slots.map { items[it] }
            .map { it to (places[id(it)]?.removeFirstOrNull() ?: Int.MAX_VALUE) }
            .sortedBy { it.second }
            .map { it.first }
        return fill(items, slots, restored)
    }

    /**
     * [items] with every song that [order] has (but [isPick]'s, smart shuffle's own) back in
     * [order]'s order, matched by [id]; songs that weren't there, picks kept and songs queued
     * since, keep their places. For smart shuffle off, which reorders all the songs to come.
     */
    fun <T> putBack(items: List<T>, order: List<T>, isPick: (T) -> Boolean, id: (T) -> String): List<T> {
        val ids = order.mapTo(HashSet(), id)
        return restore(items, { id(it) in ids && !isPick(it) }, order, id)
    }

    /**
     * How many songs at the end of [queue] AutoPlay has just added: the run of AutoPlay's songs
     * at the end whose [id]s aren't in [known].
     */
    fun <T> arrivals(queue: List<T>, known: Set<String>, isAutoplay: (T) -> Boolean, id: (T) -> String): Int =
        queue.asReversed().takeWhile { isAutoplay(it) && id(it) !in known }.size

    /**
     * Where AutoPlay's newest songs go while shuffle is on. [items] are the songs after the
     * current one, the last [added] of them just put there by AutoPlay; they're mixed and each
     * put at a random place among the songs still to come. Never ahead of the next song (it may
     * be loading, or blending in already), nor of any song [staysAhead] (those queued by hand,
     * and, when only the list's own songs were shuffled, the list's), so the queue's own order
     * rules still hold.
     */
    fun <T> placeAdded(items: List<T>, added: Int, staysAhead: (T) -> Boolean, random: Random = Random): List<T> {
        if (added <= 0 || added > items.size) return items
        val old = items.dropLast(added)
        val fresh = items.takeLast(added).let { f -> f.shuffled(random).let { m -> if (m == f && f.size >= 2) f.asReversed() else m } }
        val first = maxOf(if (old.isEmpty()) 0 else 1, old.indexOfLast(staysAhead) + 1)
        val out = old.toMutableList()
        fresh.forEach { out.add(random.nextInt(first, out.size + 1), it) }
        return out
    }

    private fun <T> fill(items: List<T>, slots: List<Int>, with: List<T>): List<T> =
        items.toMutableList().also { out -> slots.forEachIndexed { k, at -> out[at] = with[k] } }
}
