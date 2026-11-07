package com.opentune.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TrTest {
    @After fun english() = Tr.useTable("en", emptyMap())

    @Test fun looksUpByTheEnglishWordingAndFallsBackToIt() {
        Tr.useTable("hi", mapOf("Library" to "लाइब्रेरी", "{0} songs" to "{0} गाने", "{0} of {1}" to "{1} में से {0}"))
        assertEquals("लाइब्रेरी", Tr.t("Library"))
        assertEquals("Not translated yet", Tr.t("Not translated yet"))
        assertEquals("12 गाने", Tr.f("{0} songs", 12))
        // A translation can put the values in another order.
        assertEquals("10 में से 3", Tr.f("{0} of {1}", 3, 10))
        assertEquals("5 albums", Tr.f("{0} albums", 5))
    }

    @Test fun fillLeavesBracesThatAreNotPlaces() {
        assertEquals("{x} and {9} stay, 1 fills", Tr.fill("{x} and {9} stay, {0} fills", arrayOf(1)))
        assertEquals("null", Tr.fill("{0}", arrayOf(null)))
    }

    @Test fun picksTheFileForATag() {
        assertEquals("hi", Tr.resolve("hi-IN"))
        assertEquals("pt", Tr.resolve("pt-BR"))
        assertEquals("id", Tr.resolve("in-ID"))
        assertEquals("zh-TW", Tr.resolve("zh-Hant-HK"))
        assertEquals("zh-TW", Tr.resolve("zh-TW"))
        assertEquals("zh-CN", Tr.resolve("zh-Hans-CN"))
        assertEquals("en", Tr.resolve("en-GB"))
        assertEquals("en", Tr.resolve("xx"))
    }
}
