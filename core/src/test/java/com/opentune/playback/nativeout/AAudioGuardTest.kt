package com.opentune.playback.nativeout

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #72: a crash while the phone's DAC opened exclusively turns that output off at the next start. */
class AAudioGuardTest {
    @Test fun aMarkLeftByACrashIsFoundOnceAndCleared() {
        val dir = Files.createTempDirectory("guard").toFile()
        var found = 0
        AAudioGuard.start(dir) { found++ }
        assertEquals("a clean start finds nothing", 0, found)

        // Opened and played a few seconds: nothing is left behind.
        AAudioGuard.opening()
        val mark = dir.listFiles()!!.single()
        AAudioGuard.settled()
        assertFalse(mark.exists())

        // The app died while opening: the mark stays, and the next start finds it.
        AAudioGuard.opening()
        assertTrue(mark.exists())
        AAudioGuard.settled() // only to reset this process's own state for the next "start"
        File(dir, mark.name).createNewFile()
        AAudioGuard.start(dir) { found++ }
        assertEquals(1, found)
        assertFalse(mark.exists())
        AAudioGuard.start(dir) { found++ }
        assertEquals("found once, not at every start", 1, found)
    }
}
