package com.opentune.ui.welcome

import com.opentune.ui.theme.AppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WelcomeLanguageTest {
    @Test fun everyLanguageIsOfferedOnceWithThePhonesFirst() {
        listOf(Locale.US, Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("zh-TW"), Locale.forLanguageTag("sw-KE")).forEach { phone ->
            val options = WelcomeLanguage.options(phone)
            assertEquals(phone.toString(), AppLanguage.SYSTEM, options.first())
            assertEquals(phone.toString(), options.size, options.toSet().size)
            val own = WelcomeLanguage.match(phone)
            // The phone's own language is the first entry, not a second one further down.
            assertEquals(phone.toString(), AppLanguage.entries.toSet() - setOfNotNull(own), options.toSet())
        }
    }

    @Test fun englishComesSecondUnlessItIsThePhones() {
        assertEquals(AppLanguage.ENGLISH, WelcomeLanguage.options(Locale.forLanguageTag("ta-IN"))[1])
        assertFalse(AppLanguage.ENGLISH in WelcomeLanguage.options(Locale.UK))
    }

    @Test fun thePhonesLanguageIsMatchedToTheAppsOwn() {
        assertEquals(AppLanguage.INDONESIAN, WelcomeLanguage.match(Locale("in", "ID")))
        assertEquals(AppLanguage.CHINESE_TRADITIONAL, WelcomeLanguage.match(Locale.forLanguageTag("zh-HK")))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, WelcomeLanguage.match(Locale.forLanguageTag("zh-CN")))
        assertNull(WelcomeLanguage.match(Locale.forLanguageTag("sw")))
    }

    @Test fun eachIsNamedInItsOwnWords() {
        val phone = Locale.forLanguageTag("hi-IN")
        assertEquals("हिन्दी", WelcomeLanguage.nativeName(AppLanguage.SYSTEM, phone))
        assertEquals("Español", WelcomeLanguage.nativeName(AppLanguage.SPANISH, phone))
        assertEquals("Spanish", WelcomeLanguage.nameIn(AppLanguage.SPANISH, phone, Locale.ENGLISH))
        // English, read in English, needs no second name.
        assertNull(WelcomeLanguage.nameIn(AppLanguage.ENGLISH, phone, Locale.ENGLISH))
    }
}
