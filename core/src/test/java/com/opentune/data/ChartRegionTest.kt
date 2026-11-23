package com.opentune.data

import com.opentune.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartRegionTest {
    // Keys as YouTube's charts country menu gives them (taken from live answers).
    private val globalKey = "EidleHBsb3JlX2NoYXJ0c19jb3VudHJ5X21lbnVfMzE2NzY2NTY3WlogkQEoAQ%3D%3D"
    private val argentinaKey = "EidleHBsb3JlX2NoYXJ0c19jb3VudHJ5X21lbnVfMzE2NzY2NTY3QVIgkQEoAQ%3D%3D"

    /** A charts page whose country button reads [shown], with a menu of Global, Argentina and Germany. */
    private fun page(shown: String, germanyKey: String = "EidleHBsb3JlX2NoYXJ0c19jb3VudHJ5X21lbnVfMzE2NzY2NTY3REUgkQEoAQ%3D%3D") =
        Json.parseToJsonElement(
            """
            {"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
              {"musicShelfRenderer": {"subheaders": [{"musicSideAlignedItemRenderer": {"startItems": [{"musicSortFilterButtonRenderer": {
                "title": {"runs": [{"text": "$shown"}]},
                "menu": {"musicMultiSelectMenuRenderer": {"options": [
                  {"musicMultiSelectMenuItemRenderer": {"title": {"runs": [{"text": "Global"}]}, "formItemEntityKey": "$globalKey"}},
                  {"musicMultiSelectMenuItemRenderer": {"title": {"runs": [{"text": "Argentina"}]}, "formItemEntityKey": "$argentinaKey"}},
                  {"musicMultiSelectMenuItemRenderer": {"title": {"runs": [{"text": "Deutschland"}]}, "formItemEntityKey": "$germanyKey"}}
                ]}}
              }}]}}]}}
            ]}}}}]}}}
            """,
        ).jsonObject

    @Test fun aCountryWithoutChartsReadsAsGlobal() {
        // Pakistan asked for, the global charts answered.
        assertEquals(ChartRegion.GLOBAL, InnertubeParser.chartCountry(page("Global")))
    }

    @Test fun theChosenCountryIsReadFromItsKeyInAnyLanguage() {
        assertEquals("AR", InnertubeParser.chartCountry(page("Argentina")))
        // The menu in German: the name differs, the code doesn't.
        assertEquals("DE", InnertubeParser.chartCountry(page("Deutschland")))
    }

    @Test fun aPageWithoutTheMenuSaysNothing() {
        assertNull(InnertubeParser.chartCountry(Json.parseToJsonElement("""{"contents": {}}""").jsonObject))
        assertNull(InnertubeParser.chartCountryOfKey("not base64 at all"))
    }

    @Test fun aCountryWithoutChartsBorrowsOneOfItsLanguage() {
        assertEquals("MX", ChartRegion.fallback("es"))
        assertEquals("EG", ChartRegion.fallback("ar"))
        assertEquals("BR", ChartRegion.fallback("PT"))
        // English, or a language with no country of charts: the US, never the global charts.
        assertEquals("US", ChartRegion.fallback("en"))
        assertEquals("US", ChartRegion.fallback("el"))
    }

    @Test fun indianListenersStartFromTheirOwnLanguage() {
        assertEquals(ChartRegion.IndianLanguage.TAMIL, ChartRegion.indianLanguage("ta", null))
        assertEquals(ChartRegion.IndianLanguage.TELUGU, ChartRegion.indianLanguage("te", "Karnataka"))
        // The phone in English: the state they're in says which.
        assertEquals(ChartRegion.IndianLanguage.TAMIL, ChartRegion.indianLanguage("en", "Tamil Nadu"))
        assertEquals(ChartRegion.IndianLanguage.HARYANVI, ChartRegion.indianLanguage("en", " haryana "))
        assertEquals(ChartRegion.IndianLanguage.BHOJPURI, ChartRegion.indianLanguage("en", "Bihar"))
        // Hindi is what the national charts already are.
        assertNull(ChartRegion.indianLanguage("hi", "Tamil Nadu"))
        // A language with no list of its own isn't swapped for the state's.
        assertNull(ChartRegion.indianLanguage("ml", "Tamil Nadu"))
        assertNull(ChartRegion.indianLanguage("en", "Maharashtra"))
        assertNull(ChartRegion.indianLanguage("en", null))
    }
}
