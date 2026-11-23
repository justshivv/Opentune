package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricMomentTest {
    private fun line(start: Long, end: Long, text: String, sungUntil: Long = end) =
        LyricLine(start, end, text, listOf(LyricWord(text, start, sungUntil)), wordSynced = true)

    private val lines = listOf(
        line(5_000, 9_000, "one"),
        line(9_000, 30_000, "two", sungUntil = 12_000),
        line(30_000, 34_000, "three"),
    )

    @Test fun beforeTheFirstLineOnlyTheNextShows() {
        val m = lines.momentAt(1_000)
        assertNull(m.current)
        assertEquals("one", m.next?.text)
        assertEquals(5_000, m.untilMs)
    }

    @Test fun aLineAndTheOneAfterItUntilTheNextStarts() {
        val m = lines.momentAt(6_000)
        assertEquals("one", m.current?.text)
        assertEquals("two", m.next?.text)
        assertEquals(9_000, m.untilMs)
        assertEquals("one", lines.momentAt(8_999).current?.text)
        assertEquals("two", lines.momentAt(9_000).current?.text)
    }

    @Test fun aLineBeforeABreakEndsWhenItsWordsDo() {
        val sung = lines.momentAt(10_000)
        assertEquals("two", sung.current?.text)
        assertEquals(12_000, sung.untilMs)
        val gap = lines.momentAt(12_000)
        assertNull(gap.current)
        assertEquals("three", gap.next?.text)
        assertEquals(30_000, gap.untilMs)
    }

    @Test fun theLastLineHasNothingAfterIt() {
        val m = lines.momentAt(31_000)
        assertEquals("three", m.current?.text)
        assertNull(m.next)
        assertEquals(Long.MAX_VALUE, m.untilMs)
    }

    @Test fun noLinesNoMoment() {
        val m = emptyList<LyricLine>().momentAt(1_000)
        assertNull(m.current)
        assertNull(m.next)
        assertEquals(Long.MAX_VALUE, m.untilMs)
    }

    @Test fun parsedLyricsWithAShortBreakKeepTheLineLit() {
        val parsed = LrcParser.parse("[00:05.00]a b c\n[00:08.00]d e f")
        assertEquals("a b c", parsed.momentAt(7_900).current?.text)
    }
}
