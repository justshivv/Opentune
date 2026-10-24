package com.opentune.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePointsTest {
    private val hour = 60 * 60_000L

    @Test fun onlyLongTracksKeepTheirPlace() {
        ResumePoints.save("song", 2 * 60_000, 4 * 60_000)
        assertNull(ResumePoints.resumeAt("song"))
        ResumePoints.save("talk", 25 * 60_000, hour)
        assertEquals(25 * 60_000L, ResumePoints.resumeAt("talk"))
        assertEquals(25f / 60f, ResumePoints.points.value.getValue("talk").fraction, 0.001f)
    }

    @Test fun theStartChangesNothingAndTheEndForgets() {
        ResumePoints.save("mix", 10 * 60_000, hour)
        ResumePoints.save("mix", 10_000, hour)
        assertEquals(10 * 60_000L, ResumePoints.resumeAt("mix"))
        ResumePoints.save("mix", hour - 5_000, hour)
        assertNull(ResumePoints.resumeAt("mix"))
    }
}
