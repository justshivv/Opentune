package com.opentune.ui.remote

import com.opentune.data.remote.RemoteKey
import com.opentune.data.remote.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the trackpad does with touches: the gestures, and what must not be mistaken for one. */
class TrackpadEngineTest {
    private val sent = ArrayList<RemoteMessage>()
    private val felt = ArrayList<TrackpadEngine.Feel>()
    private var config = TrackpadConfig()
    private val pad = TrackpadEngine({ sent += it }, { config }, { felt += it })

    /** A finger drags [total] dp along x over [ms], in 8 ms events, then lifts. */
    private fun swipe(fingers: Int, total: Float, ms: Long, start: Long = 0L, dy: Float = 0f) {
        var t = start
        pad.onEvent(t, fingers, 0f, 0f, 8)
        val steps = (ms / 8).toInt().coerceAtLeast(1)
        repeat(steps) { t += 8; pad.onEvent(t, fingers, total / steps, dy / steps, 8) }
        pad.onRelease(t + 8)
    }

    private fun moves() = sent.filterIsInstance<RemoteMessage.Move>()
    private fun scrolls() = sent.filterIsInstance<RemoteMessage.Scroll>()

    @Test fun aQuickTapClicks() {
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onEvent(40, 1, 1f, 0.5f, 8); pad.onRelease(120)
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Click()), sent)
        assertEquals(listOf(TrackpadEngine.Feel.TAP), felt)
    }

    @Test fun tapToClickCanBeTurnedOff() {
        config = config.copy(tapToClick = false)
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onRelease(100)
        assertTrue(sent.isEmpty())
    }

    @Test fun aSlowTapIsNotAClick() {
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onRelease(400)
        assertTrue(sent.none { it is RemoteMessage.Click })
    }

    @Test fun aSwipeMovesTheCursorTheWayTheFingerWent() {
        swipe(1, total = 120f, ms = 300, dy = -40f)
        assertTrue(sent.none { it is RemoteMessage.Click })
        val dx = moves().sumOf { it.dx.toDouble() }; val dy = moves().sumOf { it.dy.toDouble() }
        assertTrue("moved right ($dx)", dx > 100)
        assertTrue("and up ($dy)", dy < -30)
    }

    @Test fun nothingIsLostBeforeTheSlopIsCrossed() {
        // Three small steps that together pass the slop: all of it arrives once it does.
        pad.onEvent(0, 1, 0f, 0f, 8)
        pad.onEvent(8, 1, 2f, 0f, 8); pad.onEvent(16, 1, 2f, 0f, 8)
        assertTrue(moves().isEmpty())
        pad.onEvent(24, 1, 2f, 0f, 8)
        assertTrue(moves().isNotEmpty())
        assertTrue(moves().sumOf { it.dx.toDouble() } >= 6 * 0.8 - 0.01)
    }

    @Test fun aFasterFlickGoesFartherThanASlowDragOfTheSameLength() {
        swipe(1, total = 100f, ms = 800)
        val slow = moves().sumOf { it.dx.toDouble() }
        sent.clear()
        swipe(1, total = 100f, ms = 80, start = 5_000)
        val fast = moves().sumOf { it.dx.toDouble() }
        assertTrue("fast $fast > slow $slow", fast > slow * 1.5)
    }

    @Test fun theSpeedSettingScalesTheCursor() {
        swipe(1, total = 100f, ms = 300)
        val normal = moves().sumOf { it.dx.toDouble() }
        sent.clear(); config = config.copy(speed = 2f)
        swipe(1, total = 100f, ms = 300, start = 5_000)
        assertEquals(normal * 2, moves().sumOf { it.dx.toDouble() }, normal * 0.01)
    }

    @Test fun holdingStillThenMovingDrags() {
        pad.onEvent(0, 1, 0f, 0f, 8)
        pad.onTick(200)
        assertTrue(sent.isEmpty())
        pad.onTick(460)
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Press(true)), sent)
        pad.onEvent(500, 1, 30f, 0f, 16); pad.onEvent(520, 1, 30f, 0f, 16)
        assertTrue(moves().isNotEmpty())
        pad.onRelease(600)
        assertEquals(RemoteMessage.Press(false), sent.last())
        assertTrue("no click after a drag", sent.none { it is RemoteMessage.Click })
        assertEquals(listOf(TrackpadEngine.Feel.GRAB, TrackpadEngine.Feel.RELEASE), felt)
    }

    @Test fun aFingerThatHasMovedDoesNotStartADrag() {
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onEvent(50, 1, 40f, 0f, 16)
        pad.onTick(600)
        assertTrue(sent.none { it is RemoteMessage.Press })
        assertEquals(null, pad.nextTickIn(600))
    }

    @Test fun twoFingersScrollAndTheContentFollowsThem() {
        swipe(2, total = 0f, ms = 240, dy = 120f)   // fingers move down
        assertTrue(moves().isEmpty())
        assertTrue("scrolled down: ${scrolls().sumOf { it.dy.toDouble() }}", scrolls().sumOf { it.dy.toDouble() } > 100)
        sent.clear(); config = config.copy(naturalScroll = false)
        swipe(2, total = 0f, ms = 240, dy = 120f, start = 5_000)
        assertTrue(scrolls().sumOf { it.dy.toDouble() } < -100)
    }

    @Test fun aTwoFingerTapIsBackAndThreeIsHome() {
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onEvent(10, 2, 0f, 0f, 8); pad.onRelease(150)
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Key(RemoteKey.BACK)), sent)
        sent.clear()
        pad.onEvent(1_000, 1, 0f, 0f, 8); pad.onEvent(1_010, 2, 0f, 0f, 8); pad.onEvent(1_020, 3, 1f, 0f, 8); pad.onRelease(1_150)
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Key(RemoteKey.HOME)), sent)
        assertEquals(listOf(TrackpadEngine.Feel.BACK, TrackpadEngine.Feel.HOME), felt)
    }

    @Test fun aSlowTwoFingerHoldIsNotBack() {
        pad.onEvent(0, 2, 0f, 0f, 8); pad.onRelease(900)
        assertTrue(sent.isEmpty())
    }

    @Test fun aSecondFingerLiftingLeavesTheCursorAlone() {
        // Two fingers down, one lifts and the other drifts: that isn't a one-finger move.
        pad.onEvent(0, 2, 0f, 0f, 8)
        pad.onEvent(50, 1, 30f, 0f, 16)
        pad.onRelease(100)
        assertTrue(sent.isEmpty())
    }

    @Test fun eachGestureStartsFresh() {
        pad.onEvent(0, 1, 0f, 0f, 8); pad.onEvent(30, 1, 50f, 0f, 16); pad.onRelease(60)
        sent.clear()
        pad.onEvent(1_000, 1, 0f, 0f, 8); pad.onRelease(1_080)
        assertEquals(listOf<RemoteMessage>(RemoteMessage.Click()), sent)
    }
}
