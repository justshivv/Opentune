package com.opentune.data.model

import com.opentune.cast.Dlna
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * The app sets the default locale to the language picked in Settings. Text a machine reads back
 * (a song's length, a DLNA time) has to stay in Western digits whatever that is.
 */
class DurationTextLocaleTest {
    private lateinit var saved: Locale

    @Before fun arabicDigits() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-u-nu-arab"))
    }

    @After fun restore() = Locale.setDefault(saved)

    @Test fun aPlainFormatReallyWritesOtherDigitsHere() {
        // What the old code did: if this stops writing Arabic-Indic digits, the test below proves nothing.
        assertNotEquals("3:05", "%d:%02d".format(3, 5))
    }

    @Test fun durationTextReadsBackInAnyLanguage() {
        assertEquals("3:05", durationTextOf(185_000))
        assertEquals("1:02:03", durationTextOf(3_723_000))
        assertEquals(185_000L, durationTextOf(185_000).durationMillis())
        assertEquals(3_723_000L, durationTextOf(3_723_000).durationMillis())
        assertEquals("0:00", durationTextOf(-5))
    }

    @Test fun dlnaTimesAreWesternDigits() {
        assertEquals("0:03:25", Dlna.clock(205_000))
        assertEquals(205_000L, Dlna.parseClock(Dlna.clock(205_000)))
    }
}
