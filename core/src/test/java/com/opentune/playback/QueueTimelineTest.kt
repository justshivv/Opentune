package com.opentune.playback

import com.opentune.data.model.QueueTier
import com.opentune.data.model.QueueTier.AUTOPLAY
import com.opentune.data.model.QueueTier.CONTEXT
import com.opentune.data.model.QueueTier.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTimelineTest {

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        assertEquals(2, QueueTimeline.userInsertIndex(listOf(CONTEXT, CONTEXT, USER, CONTEXT), current = 1, next = true))
    }

    @Test
    fun addToQueueGoesAfterTheOtherHandQueuedSongs() {
        assertEquals(4, QueueTimeline.userInsertIndex(listOf(CONTEXT, CONTEXT, USER, USER, CONTEXT), current = 1, next = false))
        assertEquals(3, QueueTimeline.userInsertIndex(listOf(CONTEXT, CONTEXT, CONTEXT), current = 2, next = false))
    }

    @Test
    fun goingBackIsAPlainJump() {
        assertNull(QueueTimeline.jump(listOf(CONTEXT, USER, CONTEXT), current = 2, target = 0))
    }

    @Test
    fun skippingAheadInTheListKeepsHandQueuedSongsNext() {
        // 0 playing, 1 queued by hand, 2 and 3 the album; tap 3.
        val jump = QueueTimeline.jump(listOf(CONTEXT, USER, CONTEXT, CONTEXT), current = 0, target = 3)!!
        assertEquals(listOf(0, 2, 3, 1), jump.order)
        assertEquals(2, jump.target)
    }

    @Test
    fun aJumpPastNoHandQueuedSongsChangesNothing() {
        assertNull(QueueTimeline.jump(listOf(CONTEXT, CONTEXT, CONTEXT, USER), current = 0, target = 2))
    }

    @Test
    fun tappingAHandQueuedSongDropsTheOnesPassed() {
        val jump = QueueTimeline.jump(listOf(CONTEXT, USER, USER, USER, CONTEXT), current = 0, target = 2)!!
        assertEquals(listOf(0, 2, 3, 4), jump.order)
        assertEquals(1, jump.target)
    }

    @Test
    fun tappingAnAutoplaySongStartsAStation() {
        val jump = QueueTimeline.jump(listOf(CONTEXT, CONTEXT, USER, AUTOPLAY, AUTOPLAY, AUTOPLAY), current = 0, target = 4)!!
        assertEquals(listOf(0, 4, 2, 5), jump.order)
        assertTrue(jump.promote)
    }

    @Test
    fun playedHandQueuedSongsOnlyCountOnceTheListPlays() {
        assertEquals(listOf(1), QueueTimeline.playedUser(listOf(CONTEXT, USER, CONTEXT), current = 2))
        assertTrue(QueueTimeline.playedUser(listOf(CONTEXT, USER, USER), current = 2).isEmpty())
    }

    @Test
    fun editsReachTheOrder() {
        val tiers: List<QueueTier> = listOf(CONTEXT, USER, AUTOPLAY, AUTOPLAY, USER, AUTOPLAY)
        val order = QueueTimeline.jump(tiers, current = 0, target = 3)!!.order
        val (removals, moves) = QueueTimeline.edits(tiers.size, order)
        val list = tiers.indices.toMutableList()
        removals.forEach { list.removeAt(it) }
        moves.forEach { (from, to) -> list.add(to, list.removeAt(from)) }
        assertEquals(order, list)
    }
}
