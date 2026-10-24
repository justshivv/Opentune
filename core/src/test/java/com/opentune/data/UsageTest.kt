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

    @Test fun totalsWaitHoursUnlessADayHasEnded() {
        val now = utc(2026, 10, 9)
        val today = setOf("20261009|1.0.0|IST")
        assertEquals(true, Usage.due(0L, now, today, "20261009"))
        assertEquals(false, Usage.due(now - 60_000, now, today, "20261009"))
        assertEquals(true, Usage.due(now - Usage.REPORT_EVERY_MS, now, today, "20261009"))
        assertEquals(true, Usage.due(now - 60_000, now, today + "20261008|1.0.0|IST", "20261009"))
        // A clock set back doesn't hold the totals for good.
        assertEquals(true, Usage.due(now + 60_000, now, today, "20261009"))
    }

    @Test fun keepsOddVersionNamesToSafeCharacters() {
        assertEquals("ver-0.3.6-beta1x", Usage.periodKeys(0, "0.3.6-beta 1 (x)")[1])
    }
}

