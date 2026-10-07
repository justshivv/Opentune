package com.opentune.ui.components

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RubberBandTest {
    private val effect = RubberBandOverscrollFactory.createOverscrollEffect()

    /** Drags by [dy]; the list scrolls only while [canScroll]. Returns what reached the list. */
    private fun drag(dy: Float, canScroll: (Float) -> Boolean): Float {
        var scrolled = 0f
        effect.applyToScroll(Offset(0f, dy), NestedScrollSource.UserInput) { d ->
            if (canScroll(d.y)) { scrolled += d.y; d } else Offset.Zero
        }
        return scrolled
    }

    @Test
    fun pushingBackAfterALongPullScrollsAtOnce() {
        // At the end of the list: a long drag past it in 20 px steps.
        repeat(100) { drag(-20f) { false } }
        assertTrue(effect.isInProgress)
        // Unmeasured, the stretch is a flat 0.55 of the drag: 1100 px shown.
        // Pushing 2000 px back takes up exactly those 1100 px, one to one,
        // and the other 900 px scroll the list.
        var reached = 0f
        repeat(100) { reached += drag(20f) { true } }
        assertFalse(effect.isInProgress)
        assertEquals(900f, reached, 0.5f)
    }

    @Test
    fun stretchGrowsSlowerThanTheFinger() {
        var first = 0f
        repeat(10) { first += drag(-10f) { false } }
        assertEquals(0f, first, 0f)
        assertTrue(effect.isInProgress)
    }

    /** Runs a fling against a clock that notes whether the content moved off its place on any frame. */
    private fun fling(velocity: Float, performFling: suspend (Velocity) -> Velocity): Boolean {
        var moved = false
        val clock = object : MonotonicFrameClock {
            var now = 0L
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                now += 16_000_000L
                return onFrame(now).also { if (effect.isInProgress) moved = true }
            }
        }
        runBlocking(clock) { effect.applyToFling(Velocity(0f, velocity), performFling) }
        return moved
    }

    @Test
    fun flingThatEndsMidListDoesNotBounce() {
        // The list takes all of the fling, as it does anywhere but its ends.
        val moved = fling(-6_000f) { it }
        assertFalse(moved)
        assertFalse(effect.isInProgress)
    }

    @Test
    fun flingThatHitsTheEndBouncesWithWhatIsLeft() {
        // The list used half the fling and stopped at its end.
        val moved = fling(-6_000f) { it / 2f }
        assertTrue(moved)
        assertFalse(effect.isInProgress)
    }

    @Test
    fun flingWhileStretchedStillScrolls() {
        // A bounce cut short by a finger leaves the content off its place;
        // flicking the same way must still fling the list, not only spring back.
        repeat(10) { drag(-20f) { false } }
        assertTrue(effect.isInProgress)
        var flung = 0
        fling(-6_000f) { flung++; it }
        assertEquals(1, flung)
        assertFalse(effect.isInProgress)
    }
}
