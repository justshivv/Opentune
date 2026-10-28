package com.opentune.desktop

import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.LyricWord
import org.junit.Test
import org.junit.Assert.assertEquals

class LyricTextTest {
    @Test fun syllablesStayJoinedAndWhitespaceIsPreserved() {
        val line = LyricLine(0, 3000, "Beautiful night", listOf(
            LyricWord("Beau", 0, 500), LyricWord("tiful ", 500, 1500), LyricWord("night", 1500, 3000),
        ), true)
        assertEquals("Beautiful night", lyricDisplayText(line))
    }
}
