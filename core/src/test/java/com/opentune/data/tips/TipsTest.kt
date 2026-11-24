package com.opentune.data.tips

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TipsTest {
    private var clock = 0L

    @Before fun fresh() {
        Tips.clearForTest()
        Tips.now = { clock }
    }

    @After fun clean() = Tips.clearForTest()

    @Test fun aTipShowsOnceAndNeverAfterItIsClosed() {
        assertTrue(Tips.request(Tip.SWIPE_ROW))
        assertEquals(Tip.SWIPE_ROW, Tips.active.value)
        Tips.dismiss(Tip.SWIPE_ROW)
        assertNull(Tips.active.value)
        assertFalse(Tips.request(Tip.SWIPE_ROW))
        assertFalse(Tips.wanted(Tip.SWIPE_ROW))
    }

    @Test fun onlyOneTipIsUpAtATime() {
        assertTrue(Tips.request(Tip.HOLD_TILE))
        assertFalse(Tips.request(Tip.SWIPE_ROW))
        // Asking again for the one that's up is fine.
        assertTrue(Tips.request(Tip.HOLD_TILE))
        Tips.dismiss(Tip.HOLD_TILE)
        clock += TipRules.QUIET_MS
        assertTrue(Tips.request(Tip.SWIPE_ROW))
    }

    @Test fun doingTheGestureRetiresTheTipEvenUnseen() {
        Tips.done(Tip.PLAYER)
        assertFalse(Tips.request(Tip.PLAYER))
        assertTrue(Tip.PLAYER in Tips.seen.value)
    }

    @Test fun noTipsDuringTheWelcome() {
        Tips.welcoming = { true }
        assertFalse(Tips.request(Tip.SWIPE_ROW))
        Tips.welcoming = { false }
        assertTrue(Tips.request(Tip.SWIPE_ROW))
    }

    @Test fun theNextTipWaitsAMomentAfterOneGoes() {
        assertTrue(Tips.request(Tip.HOLD_TILE))
        Tips.dismiss(Tip.HOLD_TILE)
        assertFalse(Tips.request(Tip.SWIPE_ROW))
        clock += TipRules.QUIET_MS - 1
        assertFalse(Tips.request(Tip.SWIPE_ROW))
        clock += 1
        assertTrue(Tips.request(Tip.SWIPE_ROW))
    }

    @Test fun recognisingSongsWaitsForTheSecondLaunch() {
        assertFalse(Tips.request(Tip.RECOGNIZE))
        // Opened a second time (the process may well have lived on in between, playing).
        Tips.opened()
        assertTrue(Tips.request(Tip.RECOGNIZE))
    }

    @Test fun aTipGoneTooSoonComesBackOneReadDoesNot() {
        assertTrue(Tips.request(Tip.MINI_PLAYER))
        clock += 500
        Tips.withdraw(Tip.MINI_PLAYER)
        assertNull(Tips.active.value)
        assertTrue(Tips.wanted(Tip.MINI_PLAYER))
        // Not straight back: a row scrolling past would have it flicker.
        assertFalse(Tips.request(Tip.MINI_PLAYER))
        clock += TipRules.QUIET_MS

        assertTrue(Tips.request(Tip.MINI_PLAYER))
        clock += TipRules.READ_MS
        Tips.withdraw(Tip.MINI_PLAYER)
        assertNull(Tips.active.value)
        assertFalse(Tips.wanted(Tip.MINI_PLAYER))
    }

    @Test fun withdrawingAnotherTipLeavesTheOneUpAlone() {
        assertTrue(Tips.request(Tip.SWIPE_ROW))
        Tips.withdraw(Tip.PLAYER)
        assertEquals(Tip.SWIPE_ROW, Tips.active.value)
    }

    @Test fun turnedOffNothingShowsAndTheOneUpGoes() {
        assertTrue(Tips.request(Tip.SWIPE_ROW))
        Tips.setEnabled(false)
        assertNull(Tips.active.value)
        clock += TipRules.QUIET_MS
        assertFalse(Tips.request(Tip.HOLD_TILE))
        assertFalse(Tips.wanted(Tip.HOLD_TILE))
    }

    @Test fun showTipsAgainBringsEveryTipBackAndTurnsThemOn() {
        Tip.entries.forEach(Tips::done)
        Tips.setEnabled(false)
        Tips.reset()
        assertTrue(Tips.enabled.value)
        assertTrue(Tips.seen.value.isEmpty())
        assertTrue(Tips.request(Tip.SWIPE_ROW))
    }
}
