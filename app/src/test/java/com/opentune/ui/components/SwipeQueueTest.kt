package com.opentune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic of swiping a song row to play it next or queue it. */
class SwipeQueueTest {
    private val width = 1000f
    private val threshold = width * SwipeQueue.THRESHOLD
    private val fling = 2_000f

    @Test fun rightPlaysNextAndLeftQueues() {
        assertEquals(SwipeAction.PLAY_NEXT, SwipeQueue.actionFor(10f, rtl = false))
        assertEquals(SwipeAction.ADD_TO_QUEUE, SwipeQueue.actionFor(-10f, rtl = false))
        assertNull(SwipeQueue.actionFor(0f, rtl = false))
    }

    @Test fun rightToLeftMirrorsTheDirections() {
        assertEquals(SwipeAction.PLAY_NEXT, SwipeQueue.actionFor(-10f, rtl = true))
        assertEquals(SwipeAction.ADD_TO_QUEUE, SwipeQueue.actionFor(10f, rtl = true))
    }

    @Test fun aDirectionWithNothingToDoDoesntMove() {
        assertEquals(0f, SwipeQueue.allowed(50f, rtl = false, canPlayNext = false, canAddToQueue = true), 0f)
        assertEquals(-50f, SwipeQueue.allowed(-50f, rtl = false, canPlayNext = false, canAddToQueue = true), 0f)
        // Mirrored: in right-to-left, a drag to the right is Add to queue.
        assertEquals(0f, SwipeQueue.allowed(50f, rtl = true, canPlayNext = true, canAddToQueue = false), 0f)
    }

    @Test fun theRowFollowsWithResistanceAndNeverRunsAway() {
        // Before the threshold: a steady share of the finger's travel.
        assertEquals(100f * SwipeQueue.FOLLOW, SwipeQueue.resisted(100f, width), 1e-3f)
        assertEquals(-100f * SwipeQueue.FOLLOW, SwipeQueue.resisted(-100f, width), 1e-3f)
        // After it, each extra pixel moves the row less, up to a limit.
        val a = SwipeQueue.resisted(600f, width)
        val b = SwipeQueue.resisted(700f, width)
        val c = SwipeQueue.resisted(800f, width)
        assertTrue(a > threshold)
        assertTrue("still gives", c > b)
        assertTrue("less each time", c - b < b - a)
        assertTrue(SwipeQueue.resisted(100_000f, width) <= width * SwipeQueue.MAX_TRAVEL)
        // Continuous at the threshold.
        val atLine = threshold / SwipeQueue.FOLLOW
        assertEquals(threshold, SwipeQueue.resisted(atLine, width), 1e-2f)
        assertEquals(threshold, SwipeQueue.resisted(atLine + 0.01f, width), 1e-1f)
        assertEquals(0f, SwipeQueue.resisted(100f, 0f), 0f)
    }

    @Test fun progressRunsToOneAtTheThreshold() {
        assertEquals(0f, SwipeQueue.progress(0f, width), 0f)
        assertEquals(0.5f, SwipeQueue.progress(threshold / 2, width), 1e-4f)
        assertEquals(1f, SwipeQueue.progress(-threshold * 2, width), 0f)
    }

    @Test fun armsAtTheThresholdWithALittleGiveOnTheWayBack() {
        assertFalse(SwipeQueue.armed(threshold - 1f, width, wasArmed = false))
        assertTrue(SwipeQueue.armed(threshold, width, wasArmed = false))
        assertTrue(SwipeQueue.armed(-threshold, width, wasArmed = false))
        // Once armed, it holds until the row is clearly back under the line.
        assertTrue(SwipeQueue.armed(threshold * 0.95f, width, wasArmed = true))
        assertFalse(SwipeQueue.armed(threshold * 0.85f, width, wasArmed = true))
    }

    @Test fun lettingGoPastTheThresholdActs() {
        assertEquals(SwipeAction.PLAY_NEXT, SwipeQueue.onRelease(threshold + 5f, 0f, width, fling, rtl = false, armed = true))
        assertEquals(SwipeAction.ADD_TO_QUEUE, SwipeQueue.onRelease(-threshold - 5f, 0f, width, fling, rtl = false, armed = true))
        assertEquals(SwipeAction.ADD_TO_QUEUE, SwipeQueue.onRelease(threshold + 5f, 0f, width, fling, rtl = true, armed = true))
    }

    @Test fun lettingGoShortOfItDoesNothing() {
        assertNull(SwipeQueue.onRelease(threshold - 5f, 0f, width, fling, rtl = false, armed = false))
        assertNull(SwipeQueue.onRelease(0f, fling * 2, width, fling, rtl = false, armed = false))
    }

    @Test fun aFlickOnwardActsEarly() {
        val partWay = width * SwipeQueue.FLING_MIN_TRAVEL + 1f
        assertEquals(SwipeAction.PLAY_NEXT, SwipeQueue.onRelease(partWay, fling, width, fling, rtl = false, armed = false))
        assertEquals(SwipeAction.ADD_TO_QUEUE, SwipeQueue.onRelease(-partWay, -fling, width, fling, rtl = false, armed = false))
        // Too slow, backwards, or barely moved: nothing.
        assertNull(SwipeQueue.onRelease(partWay, fling / 2, width, fling, rtl = false, armed = false))
        assertNull(SwipeQueue.onRelease(partWay, -fling, width, fling, rtl = false, armed = false))
        assertNull(SwipeQueue.onRelease(width * SwipeQueue.FLING_MIN_TRAVEL / 2, fling, width, fling, rtl = false, armed = false))
    }

    @Test fun aFlickBackFromPastTheThresholdCancels() {
        assertNull(SwipeQueue.onRelease(threshold + 5f, -fling, width, fling, rtl = false, armed = true))
        // A slow drift back still counts as letting go past the line.
        assertEquals(SwipeAction.PLAY_NEXT, SwipeQueue.onRelease(threshold + 5f, -fling / 4, width, fling, rtl = false, armed = true))
    }
}

class BubblePopTest {
    @Test fun swellsThenShrinksAndFadesAway() {
        assertEquals(1f, SwipeQueue.bubble(0f), 0.001f)
        assertEquals(SwipeQueue.SWELL, SwipeQueue.bubble(SwipeQueue.SWELL_SHARE), 0.001f)
        assertEquals(SwipeQueue.POPPED, SwipeQueue.bubble(1f), 0.001f)
        assertEquals(1f, SwipeQueue.bubbleAlpha(SwipeQueue.SWELL_SHARE), 0.001f)
        assertEquals(0f, SwipeQueue.bubbleAlpha(1f), 0.001f)
    }

    @Test fun eachCopyOfASongKeepsItsKeyWhenAnotherGoes() {
        fun s(id: String) = com.opentune.data.model.Song(id, id, "", null)
        val before = com.opentune.ui.library.songKeys(listOf(s("a"), s("b"), s("a"), s("c")))
        val after = com.opentune.ui.library.songKeys(listOf(s("a"), s("a"), s("c")))
        assertEquals(listOf("a#1", "b#1", "a#2", "c#1"), before)
        assertEquals(listOf(before[0], before[2], before[3]), after)
    }
}
