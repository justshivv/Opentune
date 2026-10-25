package com.opentune.ui.player

import androidx.compose.ui.geometry.Rect
import com.opentune.ui.components.PlayerMorph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PaneFlightTest {
    private val flight = PaneFlight()
    private val large = Rect(24f, 100f, 324f, 400f)
    private val small = Rect(24f, 80f, 88f, 144f)

    @After fun reset() = flight.end()

    @Test fun cancellingAnOldFlightDoesNotHideTheNewOne() {
        flight.start(large, toSmall = true)
        val firstRun = flight.runs
        flight.start(small, toSmall = false)
        flight.endRun(firstRun)
        assertTrue(flight.active)
        assertTrue(PlayerMorph.flying)
        assertEquals(small, flight.from)
        flight.endRun(flight.runs)
        assertFalse(flight.active)
        assertFalse(PlayerMorph.flying)
    }

    @Test fun aReversedFlightStartsAtItsOwnCoverNotThePreviousEnd() = runBlocking {
        flight.start(large, toSmall = true)
        flight.t.snapTo(1f)
        flight.primed = true
        flight.start(small, toSmall = false)
        assertEquals(0f, flight.progress, 0f)
        assertEquals(large, flight.target(small, large))
    }

    @Test fun aHeaderAlreadyLaidOutDoesNotBlockTheShrink() {
        flight.lastSmall = small
        flight.start(large, toSmall = true)
        assertEquals(small, flight.target(null, null))
    }

    @Test fun aSlidingOrNotYetMeasuredHeaderDoesNotDelayOrMoveTheDestination() {
        flight.start(large, toSmall = true, destination = small)
        assertEquals(small, flight.target(null, null))
        val stillSliding = Rect(small.left, small.top + 26f, small.right, small.bottom + 26f)
        assertEquals(small, flight.target(stillSliding, null))
    }
}
