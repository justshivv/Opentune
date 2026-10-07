package com.opentune.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeBehaviourTest {
    private val scroll = ChromeScrollConnection(48f)

    private fun drag(y: Float) = scroll.onPostScroll(Offset(0f, y), Offset.Zero, NestedScrollSource.UserInput)

    @Test fun deliberateScrollFoldsAndShorterUpwardScrollReveals() {
        assertEquals(Offset.Zero, drag(-30f))
        assertFalse(scroll.inline)
        drag(-20f)
        assertTrue(scroll.inline)
        drag(12f)
        assertTrue(scroll.inline)
        drag(13f)
        assertFalse(scroll.inline)
    }

    @Test fun smallDirectionChangesDoNotMakeNavigationFlicker() {
        repeat(20) { drag(-12f); drag(12f) }
        assertFalse(scroll.inline)
        drag(-50f)
        repeat(20) { drag(8f); drag(-8f) }
        assertTrue(scroll.inline)
    }

    @Test fun flingsAndHorizontalShelvesDoNotHideNavigation() {
        scroll.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.SideEffect)
        scroll.onPostScroll(Offset(-300f, 0f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(scroll.inline)
    }

    @Test fun pullAtTopRevealsButOverscrollAtBottomDoesNotHide() {
        scroll.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput)
        assertFalse(scroll.inline)
        drag(-60f)
        scroll.onPostScroll(Offset.Zero, Offset(0f, 10f), NestedScrollSource.UserInput)
        assertFalse(scroll.inline)
    }

    @Test fun lensStaysInsideRailDuringOvershootAndReversal() {
        listOf(-70f to 40f, 100f to 20f, 250f to 400f, -50f to 400f).forEach { (left, right) ->
            val bounds = dockLensBounds(left, right, slot = 60f, track = 300f)
            assertTrue(bounds.start >= 0f)
            assertTrue(bounds.width >= 60f * 0.82f)
            assertTrue(bounds.start + bounds.width <= 300f)
        }
    }

    @Test fun settledLensMatchesTheSelectedSlot() {
        assertEquals(DockLensBounds(120f, 60f), dockLensBounds(120f, 180f, 60f, 300f))
        assertEquals(DockLensBounds(0f, 0f), dockLensBounds(0f, 0f, 0f, 0f))
    }
}
