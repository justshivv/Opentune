package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTranslatorJoinTest {
    @Test fun sentencesWithoutTheirOwnSpaceAreSpaced() {
        // Two Urdu sentences of one line, the first without its trailing space.
        assertEquals("میں تم سے پیار کرتا ہوں۔ ہمیشہ", LyricsTranslator.joinSegments(listOf("میں تم سے پیار کرتا ہوں۔", "ہمیشہ"), "ur"))
        assertEquals("أحبك دائما. إلى الأبد", LyricsTranslator.joinSegments(listOf("أحبك دائما.", "إلى الأبد"), "ar"))
    }

    @Test fun spacesAndLineBreaksAlreadyThereAreKept() {
        assertEquals("Hola. Adiós", LyricsTranslator.joinSegments(listOf("Hola. ", "Adiós"), "es"))
        assertEquals("one\ntwo", LyricsTranslator.joinSegments(listOf("one\n", "two"), "en"))
        assertEquals("a, b", LyricsTranslator.joinSegments(listOf("a,", " b"), "en"))
    }

    @Test fun languagesWrittenWithoutSpacesStayJoined() {
        assertEquals("我爱你。永远", LyricsTranslator.joinSegments(listOf("我爱你。", "永远"), "zh-CN"))
        assertEquals("愛してる。ずっと", LyricsTranslator.joinSegments(listOf("愛してる。", "ずっと"), "ja"))
    }
}
