package com.opentune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test fun widthClassesFollowMaterialsBreakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(320))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(1280))
    }

    @Test fun phonesKeepTheDock() {
        val playing = appLayout(411, 891, hasSong = true)
        assertEquals(NavChrome.DOCK, playing.nav)
        assertEquals(NowPlaying.DOCK, playing.nowPlaying)
        assertNull(playing.contentMaxWidthDp)
        assertFalse(playing.sidePlayer)
        assertEquals(NowPlaying.NONE, appLayout(411, 891, hasSong = false).nowPlaying)
        // Just short of medium, still a phone.
        assertEquals(NavChrome.DOCK, appLayout(599, 900, hasSong = true).nav)
    }

    @Test fun mediumGetsTheRailAndAMiniPlayerUnderThePage() {
        val l = appLayout(700, 900, hasSong = true)
        assertEquals(WidthClass.MEDIUM, l.width)
        assertEquals(NavChrome.RAIL, l.nav)
        assertEquals(NowPlaying.BAR, l.nowPlaying)
        assertEquals(720, l.contentMaxWidthDp)
        assertFalse("a medium window never docks the player", l.sidePlayer)
        assertEquals(NowPlaying.NONE, appLayout(700, 900, hasSong = false).nowPlaying)
    }

    @Test fun expandedDocksThePlayerWhileASongIsLoaded() {
        val l = appLayout(1280, 800, hasSong = true)
        assertEquals(WidthClass.EXPANDED, l.width)
        assertEquals(NavChrome.RAIL, l.nav)
        assertEquals(NowPlaying.PANE, l.nowPlaying)
        assertTrue(l.sidePlayer)

        val idle = appLayout(1280, 800, hasSong = false)
        assertEquals(NavChrome.RAIL, idle.nav)
        assertEquals(NowPlaying.NONE, idle.nowPlaying)
        assertFalse(idle.sidePlayer)
    }

    @Test fun theFoldedPaneIsAStripAndStillDocked() {
        val l = appLayout(1280, 800, hasSong = true, paneCollapsed = true)
        assertEquals(NowPlaying.STRIP, l.nowPlaying)
        assertTrue("the strip opens the pane, not the full-screen player", l.sidePlayer)
        // Folding means nothing where there's no pane.
        assertEquals(NowPlaying.BAR, appLayout(700, 900, hasSong = true, paneCollapsed = true).nowPlaying)
        assertEquals(NowPlaying.DOCK, appLayout(411, 891, hasSong = true, paneCollapsed = true).nowPlaying)
    }

    @Test fun aPhoneOnItsSideIsTooShortForThePane() {
        val l = appLayout(891, 411, hasSong = true)
        assertEquals(WidthClass.EXPANDED, l.width)
        assertEquals(NavChrome.RAIL, l.nav)
        assertEquals(NowPlaying.BAR, l.nowPlaying)
        assertFalse(l.sidePlayer)
        assertEquals(NowPlaying.PANE, appLayout(891, SIDE_PLAYER_MIN_HEIGHT_DP, hasSong = true).nowPlaying)
    }

    @Test fun settingsTurnEachPartOff() {
        val noSide = appLayout(1280, 800, hasSong = true, sidePlayer = false)
        assertEquals(NavChrome.RAIL, noSide.nav)
        assertEquals(NowPlaying.BAR, noSide.nowPlaying)
        assertFalse(noSide.sidePlayer)

        val phoneLike = appLayout(1280, 800, hasSong = true, wideLayout = false)
        assertEquals(NavChrome.DOCK, phoneLike.nav)
        assertEquals(NowPlaying.DOCK, phoneLike.nowPlaying)
        assertNull(phoneLike.contentMaxWidthDp)
    }

    @Test fun gridsKeepTwoColumnsOnPhonesAndGrowWider() {
        // Explore's grid inside its 12dp margins.
        assertEquals(2, gridColumns(320f - 24))
        assertEquals(2, gridColumns(411f - 24))
        assertEquals(2, gridColumns(599f - 24))
        assertEquals(3, gridColumns(720f - 24))
        assertEquals(4, gridColumns(1000f))
        assertEquals(2, gridColumns(0f))
    }
    @Test fun televisionsAlwaysHaveRailAndFullScreenPlayerRegardlessOfPhonePreferences() {
        for ((w, h) in listOf(640 to 360, 960 to 540, 1280 to 720, 1920 to 1080)) {
            val layout = appLayout(w, h, true, wideLayout = false, sidePlayer = true, television = true)
            assertEquals(NavChrome.RAIL, layout.nav)
            assertEquals(NowPlaying.BAR, layout.nowPlaying)
            assertFalse(layout.sidePlayer)
            assertNull(layout.contentMaxWidthDp)
        }
    }

}
