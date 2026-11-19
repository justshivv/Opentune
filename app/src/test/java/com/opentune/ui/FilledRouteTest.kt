package com.opentune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FilledRouteTest {
    private val encode: (String) -> String = { it.replace(" ", "%20").replace("&", "%26") }

    @Test fun pathArgumentsAreFilledIn() {
        assertEquals("playlist/a%20b", filledRoute("playlist/{id}", mapOf("id" to "a b")::get, encode))
    }

    @Test fun anOptionalArgumentLeftOutStaysOut() {
        // A new smart playlist's editor, a plain Settings and Listen together without a code.
        assertEquals("smart-edit", filledRoute("smart-edit?id={id}", { null }, encode))
        assertEquals("settings", filledRoute("settings?page={page}", { null }, encode))
        assertEquals("together", filledRoute("together?code={code}", { null }, encode))
    }

    @Test fun givenOptionalArgumentsAreKept() {
        assertEquals("smart-edit?id=s1", filledRoute("smart-edit?id={id}", mapOf("id" to "s1")::get, encode))
        val args = mapOf("id" to "MPAD", "params" to "", "title" to "Singles & EPs")
        assertEquals("artist-items/MPAD?params=&title=Singles%20%26%20EPs",
            filledRoute("artist-items/{id}?params={params}&title={title}", args::get, encode))
    }

    @Test fun onlyTheMissingOneOfSeveralIsLeftOut() {
        val args = mapOf("id" to "m", "params" to null, "title" to "Chill")
        assertEquals("mood/m?title=Chill", filledRoute("mood/{id}?params={params}&title={title}", args::get, encode))
    }
}
