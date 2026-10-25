package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RomanizerTest {
    @Test fun otherScriptsInLatinLetters() {
        assertEquals("aja piya", Romanizer.romanize("आजा पिया"))
        assertEquals("saranghae", Romanizer.romanize("사랑해"))
        assertEquals("privet", Romanizer.romanize("привет"))
        // A final consonant carries into a silent ㅇ, and ㄹ next to ㄹ is "l".
        assertEquals("hangugeo", Romanizer.romanize("한국어"))
        assertEquals("ollae", Romanizer.romanize("올래"))
    }

    @Test fun latinLinesNeedNothing() {
        assertNull(Romanizer.romanize("I'm blinded by the lights"))
    }

    @Test fun kanjiStayInJapaneseLyrics() {
        val out = Romanizer.lines(listOf("君の名は", "さよなら"))
        assertEquals("sayonara", out[1])
        assertEquals(true, out[0]!!.startsWith("君"))
    }

    @Test fun noMarkIsLeftWithoutALetter() {
        // An Urdu Punjabi line (Noor Jehan, "Nahar Wale Pool") once came out as
        // "sady akʰa◌ w̃chw◌ ñyndra◌ ãuda kai", the marks drawn on dotted circles.
        val out = Romanizer.romanize("ساڈی اکھاں وچوں نیندراں اُڈا کے")!!
        assertEquals(true, out.all { it.code < 0x80 })
        assertEquals("sady akha wchw nyndra auda kai", Romanizer.plain("sady akʰa\u0303 w\u0303chw\u25CC ñyndra\u0303 ãuda kai"))
    }
}
