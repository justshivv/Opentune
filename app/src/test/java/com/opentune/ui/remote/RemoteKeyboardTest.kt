package com.opentune.ui.remote

import com.opentune.data.remote.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote's keyboard following the TV's search field: opens on focus, types live, Enter searches, closes on blur. */
class RemoteKeyboardTest {
    private val sent = ArrayList<RemoteMessage>()
    private val keyboard = RemoteKeyboard { sent += it }

    @Test fun opensByItselfWithWhatTheFieldHolds() {
        assertFalse(keyboard.state.value.open)
        keyboard.onField(RemoteMessage.Field(true, "pink", "Search songs, albums, artists"))
        val s = keyboard.state.value
        assertTrue(s.open)
        assertEquals("pink", s.text)
        assertEquals("Search songs, albums, artists", s.hint)
        assertTrue(sent.isEmpty())
    }

    @Test fun typingGoesToTheTvAsItIsTypedAndEnterSearches() {
        keyboard.onField(RemoteMessage.Field(true, ""))
        keyboard.type("p"); keyboard.type("pi"); keyboard.type("pi") // the same twice is sent once
        keyboard.submit()
        assertEquals(listOf(RemoteMessage.Edit("p"), RemoteMessage.Edit("pi"), RemoteMessage.Edit("pi", submit = true)), sent)
        assertFalse(keyboard.state.value.open)
    }

    @Test fun closesWhenTheFieldLosesTheFocusOrTheTvGoes() {
        keyboard.onField(RemoteMessage.Field(true, "a"))
        keyboard.onField(RemoteMessage.Field(false))
        assertFalse(keyboard.state.value.open)
        assertFalse(keyboard.state.value.focused)
        keyboard.onField(RemoteMessage.Field(true, "a"))
        keyboard.onField(null) // disconnected
        assertFalse(keyboard.state.value.open)
    }

    @Test fun closedByHandItStaysClosedUntilTheFieldIsFocusedAgain() {
        keyboard.onField(RemoteMessage.Field(true, "a"))
        keyboard.dismiss()
        // Typed on the TV meanwhile: remembered, but it doesn't pop back up.
        keyboard.onField(RemoteMessage.Field(true, "ab"))
        assertFalse(keyboard.state.value.open)
        assertEquals("ab", keyboard.state.value.text)
        // The keyboard button opens it again while the field has the focus.
        assertTrue(keyboard.open())
        assertTrue(keyboard.state.value.open)
        // Focus away and back: opens by itself.
        keyboard.dismiss()
        keyboard.onField(RemoteMessage.Field(false))
        keyboard.onField(RemoteMessage.Field(true, "ab"))
        assertTrue(keyboard.state.value.open)
    }

    @Test fun theKeyboardButtonDoesNothingHereWithoutAFocusedField() {
        // An older TV never says: the button falls back to typing a whole search.
        assertFalse(keyboard.open())
        assertFalse(keyboard.state.value.open)
    }

    @Test fun whatTheTvTypesShowsWhileOpen() {
        keyboard.onField(RemoteMessage.Field(true, "a"))
        keyboard.onField(RemoteMessage.Field(true, "abc"))
        assertEquals("abc", keyboard.state.value.text)
        assertTrue(keyboard.state.value.open)
        assertTrue(sent.isEmpty())
    }

    @Test fun nothingLongerThanTheTvTakes() {
        keyboard.onField(RemoteMessage.Field(true, ""))
        keyboard.type("x".repeat(500))
        assertEquals(RemoteKeyboard.MAX, (sent.single() as RemoteMessage.Edit).text.length)
    }
}
