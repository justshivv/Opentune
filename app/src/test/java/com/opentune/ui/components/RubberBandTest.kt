package com.opentune.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
