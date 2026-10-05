package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun keepsTheNewestLinesWithTheirErrors() {
        repeat(4_100) { AppLog.add('D', "Test", "line $it") }
        AppLog.add('E', "PlaybackService", "Playback error", IllegalStateException("no stream"))
        val lines = AppLog.snapshot()
        assertEquals(4_000, lines.size)
        assertTrue(lines.first().endsWith("line 101"))
        assertTrue(lines.last().contains("E PlaybackService: Playback error"))
        assertTrue(lines.last().contains("IllegalStateException: no stream"))
    }
}
