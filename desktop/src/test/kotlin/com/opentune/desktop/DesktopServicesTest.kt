package com.opentune.desktop

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopServicesTest {
    @Test
    fun theLatestReleaseIsReadFromItsAddress() {
        assertEquals("1.1.0", Updates.versionIn("https://github.com/justshivv/Opentune/releases/tag/v1.1.0"))
        assertEquals("1.2.0-beta.1", Updates.versionIn("https://github.com/justshivv/Opentune/releases/tag/v1.2.0-beta.1"))
        assertNull(Updates.versionIn("https://github.com/justshivv/Opentune/releases"))
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Updates.newer("1.10.0", "1.9.3"))
        assertTrue(Updates.newer("1.1.1", "1.1"))
        assertFalse(Updates.newer("1.1.0", "1.1.0"))
        assertFalse(Updates.newer("1.0.9", "1.1.0"))
        // A release comes after its own pre-release, never before.
        assertTrue(Updates.newer("1.2.0", "1.2.0-beta"))
        assertFalse(Updates.newer("1.2.0-beta", "1.2.0"))
    }

    @Test
    fun theInstallCountSendsAnIdAndVersionAndNothingElse() {
        val sent = DesktopUsage.payload("3f2b8c1e-6d4a-4b7e-9a10-2c5d8e7f6a90", DesktopUsage.reportedVersion("1.1.0"), 1, LocalDate.of(2026, 11, 19))
        assertEquals(setOf("installationId", "currentVersion", "versionRevision", "days"), sent.keySet())
        assertEquals("1.1.0-desktop", sent.getString("currentVersion"))
        val day = sent.getJSONArray("days").getJSONObject(0)
        assertEquals(setOf("day", "version", "timeZone", "plays"), day.keySet())
        assertEquals(0, day.getInt("plays"))
        assertEquals("1.2.0-beta.desktop", DesktopUsage.reportedVersion("1.2.0-beta"))
        // The server's own pattern for a version (usage-api/worker.mjs).
        val pattern = Regex("""^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]{1,32})?$""")
        assertTrue(pattern.matches(DesktopUsage.reportedVersion("1.1.0")))
        assertTrue(pattern.matches(DesktopUsage.reportedVersion("1.2.0-beta")))
    }

    @Test
    fun theInstallCountIsOffUntilTurnedOn() {
        assertFalse(DesktopUsage.enabled.value)
    }
}
