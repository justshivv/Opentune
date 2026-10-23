package com.opentune.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FestivalsTest {
    private val ics = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261108
        SUMMARY:Diwali/Deepavali
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261011
        SUMMARY:First Day of Sharad Navratri
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20270825
        SUMMARY:Janmashtami (Smarta)
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261101
        SUMMARY:Daylight Saving Time ends
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261124
        SUMMARY:Guru Tegh Bahadur's Martyrdom Day
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test fun keepsCelebrationsWithCleanNames() {
        val festivals = Festivals.parse(ics)
        assertEquals(
            listOf(
                Festivals.Festival("Sharad Navratri", LocalDate.of(2026, 10, 11)),
                Festivals.Festival("Diwali/Deepavali", LocalDate.of(2026, 11, 8)),
                Festivals.Festival("Janmashtami", LocalDate.of(2027, 8, 25)),
            ),
            festivals,
        )
    }

    @Test fun leavesOutDaysThatArentCelebrations() {
        assertNull(Festivals.festiveName("Good Friday"))
        assertNull(Festivals.festiveName("Daylight Saving Time starts"))
        assertNull(Festivals.festiveName("Muharram/Ashura (tentative)"))
        assertEquals("Ramzan Id", Festivals.festiveName("Ramzan Id (tentative)"))
    }

    @Test fun asksAgainAMonthAndAHalfAfterBeingTurnedDown() {
        val day = 86_400_000L
        assertEquals(false, Festivals.shouldWait(0L, 100 * day))
        assertEquals(true, Festivals.shouldWait(100 * day, 144 * day))
        assertEquals(false, Festivals.shouldWait(100 * day, 145 * day))
    }

    @Test fun namesEachCountrysCalendar() {
        assertEquals("indian", Festivals.calendarId("IN"))
        assertEquals("usa", Festivals.calendarId("us"))
        assertEquals("pk", Festivals.calendarId("PK"))
    }
}
