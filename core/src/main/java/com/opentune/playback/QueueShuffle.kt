package com.opentune.playback

import com.opentune.data.model.QueueTier
import kotlin.random.Random

/**
 * The orders behind the player's shuffle button ([PlayerConnection.toggleShuffle]), on the songs
 * after the current one. Only the list's own songs move ([isList]); songs queued by hand and
 * AutoPlay's keep their places.
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

    private fun <T> fill(items: List<T>, slots: List<Int>, with: List<T>): List<T> =
        items.toMutableList().also { out -> slots.forEachIndexed { k, at -> out[at] = with[k] } }
}
