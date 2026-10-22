package com.opentune.playback

import com.opentune.data.model.QueueTier

/**
 * The queue's rules, as BitChord keeps them, written over the tiers of its
 * entries so they can be tested without a player. [PlayerConnection] applies
 * them to the media controller.
 *
 * Songs queued by hand stay: starting an album or a song keeps them right
 * after it, "Add to queue" puts a song after the others queued by hand and
 * before the rest of the album, and tapping a row further down the queue
 * keeps them coming next rather than skipping past them.
 */
object QueueTimeline {

    /** Indices, after [current], of the songs queued by hand. */
    fun upcomingUser(tiers: List<QueueTier>, current: Int): List<Int> =
        ((current + 1).coerceAtLeast(0) until tiers.size).filter { tiers[it] == QueueTier.USER }

    /**
     * Where a song queued by hand goes: right after the current one for Play
     * next ([next]), otherwise after the hand-queued songs already waiting.
     */
    fun userInsertIndex(tiers: List<QueueTier>, current: Int, next: Boolean): Int {
        if (tiers.isEmpty()) return 0
        val start = (current + 1).coerceIn(0, tiers.size)
        if (next) return start
        for (i in start until tiers.size) if (tiers[i] != QueueTier.USER) return i
        return tiers.size
    }

    /**
     * What tapping queue row [target] does: the order the queue's entries
     * should be in afterwards (indices into the queue as it is; entries left
     * out are removed), where [target] then sits, and whether it becomes the
     * start of a new list. Null for a plain jump, which changes no order.
     *
     * - Back, or forward past nothing queued by hand: a plain jump.
     * - Forward to a song of the list, past songs queued by hand: those are
     *   kept, and moved after the target.
     * - To a song queued by hand: the hand-queued songs passed over are dropped.
     * - To an autoplay song: it becomes the list (a new station), keeping the
     *   songs queued by hand after it; the old list and the autoplay songs
     *   passed over are dropped.
     */
    fun jump(tiers: List<QueueTier>, current: Int, target: Int): Jump? {
        if (current !in tiers.indices || target !in tiers.indices || target <= current) return null
        val history = (0..current).toList()
        val after = (target + 1 until tiers.size)
        val between = (current + 1 until target)
        return when (tiers[target]) {
            QueueTier.CONTEXT -> {
                val passedUser = between.filter { tiers[it] == QueueTier.USER }
                if (passedUser.isEmpty()) return null
                val passedContext = between.filter { tiers[it] != QueueTier.USER }
                val order = history + passedContext + target +
                    passedUser + after.filter { tiers[it] == QueueTier.USER } +
                    after.filter { tiers[it] == QueueTier.CONTEXT } + after.filter { tiers[it] == QueueTier.AUTOPLAY }
                Jump(order, history.size + passedContext.size, promote = false)
            }
            QueueTier.USER -> {
                val upcoming = current + 1 until tiers.size
                val order = history + target + after.filter { tiers[it] == QueueTier.USER } +
                    upcoming.filter { tiers[it] == QueueTier.CONTEXT } + upcoming.filter { tiers[it] == QueueTier.AUTOPLAY }
                Jump(order, history.size, promote = false)
            }
            QueueTier.AUTOPLAY -> {
                val order = history + target + (current + 1 until tiers.size).filter { tiers[it] == QueueTier.USER } +
                    after.filter { tiers[it] == QueueTier.AUTOPLAY }
                Jump(order, history.size, promote = true)
            }
        }
    }

    class Jump(val order: List<Int>, val target: Int, val promote: Boolean)

    /**
     * The hand-queued songs already played, once a song of the list is
     * playing: with repeat on, only the list goes round again.
     */
    fun playedUser(tiers: List<QueueTier>, current: Int): List<Int> {
        if (current !in tiers.indices || tiers[current] != QueueTier.CONTEXT) return emptyList()
        return (0 until current).filter { tiers[it] == QueueTier.USER }
    }

    /**
     * The moves that turn a list of [size] entries, with [order]'s entries
     * kept and the rest removed, into [order]. Removals come first, from the
     * end; each move is (from, to) on the list as it is at that step.
     */
    fun edits(size: Int, order: List<Int>): Pair<List<Int>, List<Pair<Int, Int>>> {
        val kept = order.toHashSet()
        val removals = (size - 1 downTo 0).filter { it !in kept }
        val now = (0 until size).filter { it in kept }.toMutableList()
        val moves = mutableListOf<Pair<Int, Int>>()
        order.forEachIndexed { to, entry ->
            val from = now.indexOf(entry)
            if (from != to) {
                now.add(to, now.removeAt(from))
                moves += from to to
            }
        }
        return removals to moves
    }
}
