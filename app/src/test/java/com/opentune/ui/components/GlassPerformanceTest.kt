package com.opentune.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassPerformanceTest {
    @Test fun aRunIsSlowOnlyWhenMoreThanItsShareIsLate() {
        val w = GlassPerformance.FrameWindow(size = 10, share = 0.25f)
        // Two late frames in ten: a moment's stutter, not a slow run.
        repeat(9) { i -> assertNull(w.add(i < 2)) }
        assertEquals(false, w.add(false))
        // Three late in ten: slow.
        repeat(9) { i -> assertNull(w.add(i < 3)) }
        assertEquals(true, w.add(false))
    }
}
