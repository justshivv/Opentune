package com.opentune.ui.components

import androidx.compose.ui.geometry.Rect
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.PageTransition
import com.opentune.data.settings.PerformanceProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a page grows out of its cover, and when it comes in the usual way instead. */
class CoverMorphTest {
    private val card = Rect(40f, 600f, 508f, 1068f)
    private val grow = InterfaceSettings(pageTransition = PageTransition.GROW)

    @After fun forget() = CoverMorph.clear()

    private fun open(bounds: Rect?, allowed: Boolean = true, id: String = "MPREb_a", tappedAt: Long = 1_000, now: Long = 1_200): Boolean {
        CoverMorph.tapped("MPREb_a", card = 7, bounds = bounds, cornerPx = 48f, now = tappedAt)
        return CoverMorph.arrived("page", id, "home", allowed, now)
    }

    @Test fun onlyWithGrowFromCoverAndMotionAllowed() {
        assertTrue(CoverMorph.allowed(grow))
        assertFalse("another page transition", CoverMorph.allowed(InterfaceSettings(pageTransition = PageTransition.SLIDE)))
        assertFalse("reduce animation", CoverMorph.allowed(grow.copy(reduceAnimation = true)))
        assertFalse("lite mode", CoverMorph.allowed(grow.copy(liteMode = true)))
        assertFalse("battery saver", CoverMorph.allowed(grow.copy(performanceProfile = PerformanceProfile.BATTERY_SAVER)))
        assertTrue("balanced", CoverMorph.allowed(grow.copy(performanceProfile = PerformanceProfile.BALANCED)))
    }

    @Test fun aTappedCoverGrowsAndShrinksBackIntoItsPage() {
        assertTrue(open(card))
        assertTrue(CoverMorph.opens("page"))
        assertTrue(CoverMorph.closesInto("page", "home"))
        assertFalse("back to some other page: the usual way", CoverMorph.closesInto("page", "library"))
        assertEquals("cover:page", CoverMorph.keyFor(7))
    }

    @Test fun withTheCoversPlaceUnknownThePageComesInTheUsualWay() {
        assertFalse(open(bounds = null))
        assertFalse(open(bounds = Rect(10f, 10f, 10f, 10f)))
        assertFalse(CoverMorph.opens("page"))
        assertEquals(null, CoverMorph.keyFor(7))
    }

    @Test fun withMotionReducedThePageComesInTheUsualWay() {
        assertFalse(open(card, allowed = CoverMorph.allowed(grow.copy(reduceAnimation = true))))
        assertFalse(CoverMorph.opens("page"))
    }

    @Test fun aLinkOrAnotherPageNeverGrows() {
        // Opened from a link: no cover was tapped.
        assertFalse(CoverMorph.arrived("page", "MPREb_a", "home", allowed = true, now = 5_000))
        // A cover tapped, but another page opened (a search for its name, say).
        assertFalse(open(card, id = "MPREb_b"))
        // A cover tapped long ago.
        assertFalse(open(card, tappedAt = 1_000, now = 1_000 + CoverMorph.TAP_FRESH_MS + 1))
    }

    @Test fun aTapIsUsedOnce() {
        assertTrue(open(card))
        assertFalse(CoverMorph.arrived("page2", "MPREb_a", "page", allowed = true, now = 1_300))
    }

    @Test fun aClosedPageIsForgottenOnceItHasLeft() {
        assertTrue(open(card))
        CoverMorph.shown("page", true)
        // Covered by another page: still on the stack, kept.
        CoverMorph.stackIs(listOf("home", "page", "artist"))
        CoverMorph.shown("page", false)
        assertTrue(CoverMorph.opens("page"))
        // Back to it, then back to Home: off the stack, forgotten once it has gone.
        CoverMorph.shown("page", true)
        CoverMorph.stackIs(listOf("home"))
        assertTrue("kept while it shrinks", CoverMorph.closesInto("page", "home"))
        CoverMorph.shown("page", false)
        assertFalse(CoverMorph.opens("page"))
    }

    @Test fun aPageGoneBeforeTheStackSaysSoIsForgottenToo() {
        assertTrue(open(card))
        CoverMorph.shown("page", true)
        CoverMorph.shown("page", false)
        assertTrue(CoverMorph.opens("page"))
        CoverMorph.stackIs(listOf("home"))
        assertFalse(CoverMorph.opens("page"))
    }

    @Test fun theCornerRoundsOffAsThePageGrows() {
        assertEquals(0f, CoverMorph.progress(468f, 1233f, 468f), 0.001f)
        assertEquals(0.5f, CoverMorph.progress(468f, 1232f, 850f), 0.001f)
        assertEquals(1f, CoverMorph.progress(468f, 1233f, 1300f), 0.001f)
        assertEquals("page not laid out yet", 1f, CoverMorph.progress(468f, 0f, 500f), 0.001f)
    }
}
