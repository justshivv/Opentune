package com.opentune.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserCountryTest {
    @Test fun whereThePhoneIsComesBeforeItsLanguage() {
        // Located in India on a phone set to English (US): India.
        assertEquals("IN", UserCountry.pick("IN", "in", "US"))
        // No location allowed: the mobile network's country, in upper case.
        assertEquals("IN", UserCountry.pick(null, "in", "US"))
        // Nothing but the language.
        assertEquals("US", UserCountry.pick(null, null, "US"))
        // Blanks and odd values are passed over.
        assertEquals("GB", UserCountry.pick("", "419", "gb"))
        assertNull(UserCountry.pick(null, "", null))
    }
}
