package com.opentune.data

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** The counter names the app builds, which the website's dashboard rebuilds the same way. */
class UsageTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test fun namesDaysWeeksAndMonthsInUtc() {
        val t = utc(2026, 10, 9)
        assertEquals(listOf("installs", "ver-0.3.6", "dau-20261009", "wau-2026w41", "mau-202610"), Usage.periodKeys(t, "0.3.6"))
        // ISO weeks: 1 January 2027 is a Friday, still in 2026's 53rd week; 4 January 2027 starts week 1.
        assertEquals("2026w53", Usage.week(utc(2027, 1, 1)))
        assertEquals("2027w01", Usage.week(utc(2027, 1, 4)))
        // Late on 31 December in UTC is still that day, whatever the phone's own zone.
        assertEquals("20261231", Usage.day(utc(2026, 12, 31, 23)))
        assertEquals("202612", Usage.month(utc(2026, 12, 31, 23)))
    }

    @Test fun istChangesDayAt1830UtcIncludingYearBoundary() {
        assertEquals("20261231", Usage.istDay(java.time.Instant.parse("2026-12-31T18:29:59Z").toEpochMilli()))
        assertEquals("20270101", Usage.istDay(java.time.Instant.parse("2026-12-31T18:30:00Z").toEpochMilli()))
    }

    private fun ist(at: String) = java.time.Instant.parse(at).toEpochMilli()

    @Test fun aDayIsSentOnceItsOverAndOnlyOnceADay() {
        val today = setOf("20261009|1.0.0|IST")
        val both = today + "20261008|1.0.0|IST"
        val noon = ist("2026-10-09T06:30:00Z")                                 // 12:00 IST on the 9th
        // A new installation is counted at once, today's plays and all.
        assertEquals(true, Usage.due(0L, noon, today, "20261009"))
        // After that, today's plays wait for the day to end, however long since the last report.
        assertEquals(false, Usage.due(ist("2026-10-07T06:30:00Z"), noon, today, "20261009"))
        // Yesterday is over: sent, if nothing went out yet today.
        assertEquals(true, Usage.due(ist("2026-10-08T17:00:00Z"), noon, both, "20261009"))   // last sent 22:30 IST on the 8th
        // Already sent today (00:10 IST on the 9th): nothing more until tomorrow.
        assertEquals(false, Usage.due(ist("2026-10-08T18:40:00Z"), noon, both, "20261009"))
        // A clock set back doesn't hold the totals for good.
        assertEquals(true, Usage.due(noon + 60_000, noon, both, "20261009"))
        assertEquals(true, Usage.finished("20261008|1.0.0|IST", "20261009"))
        assertEquals(false, Usage.finished("20261009|1.0.0|IST", "20261009"))
    }

    @Test fun keepsOddVersionNamesToSafeCharacters() {
        assertEquals("ver-0.3.6-beta1x", Usage.periodKeys(0, "0.3.6-beta 1 (x)")[1])
    }

    @Test fun aNewInstallationIsRetriedSoonAndALaterFailureWaits() {
        // Never counted: a failed try comes again within minutes, so the install shows straight away.
        assertEquals(Usage.FIRST_RETRY_MS, Usage.retryAfter(0L))
        assertEquals(15 * 60_000L, Usage.FIRST_RETRY_MS)
        // Counted once: a failed daily report waits a few hours before the next try.
        assertEquals(Usage.RETRY_MS, Usage.retryAfter(1_760_000_000_000L))
        assertEquals(4 * 60 * 60_000L, Usage.RETRY_MS)
    }
}
