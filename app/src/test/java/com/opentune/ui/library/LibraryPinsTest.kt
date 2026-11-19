package com.opentune.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPinsTest {
    private val everything: (String) -> Boolean = { true }

    @Test fun pinsAndUnpinsWithRoom() {
        assertEquals(listOf("a", "b"), LibraryPins.toggled(listOf("a"), "b", everything))
        assertEquals(listOf("a"), LibraryPins.toggled(listOf("a", "b"), "b", everything))
    }

    @Test fun aFullRowOfPinsInTheLibraryRefusesAnother() {
        val full = listOf("a", "b", "c", "d", "e")
        assertNull(LibraryPins.toggled(full, "f", everything))
        // Unpinning still works when full.
        assertEquals(listOf("a", "b", "c", "d"), LibraryPins.toggled(full, "e", everything))
    }

    @Test fun pinsOfListsNoLongerInTheLibraryDontTakeUpRoom() {
        val pins = listOf("gone", "a", "b", "deleted", "c")
        val present = { key: String -> key !in setOf("gone", "deleted") }
        assertEquals(listOf("a", "b", "c", "d"), LibraryPins.toggled(pins, "d", present))
    }

    @Test fun stalePinsAreKeptWhileThereIsRoom() {
        // A YouTube Music playlist out of sight while signed out keeps its pin for when it's back.
        val pins = listOf("signedOut", "a")
        assertEquals(listOf("signedOut", "a", "b"), LibraryPins.toggled(pins, "b") { it != "signedOut" })
    }
}
