package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun aShortLineBeforeABreakIsSungThenTheBreakShows() {
        // "Tum hi ho" at 10 s, the next line 30 s later: the words are sung in a few
        // seconds and the rest is an instrumental break, not a slow crawl through it.
        val lines = LrcParser.parse("[00:10.00]Tum hi ho\n[00:40.00]Ab tum hi ho", 0L)
        val sung = lines.first().sungUntilMs()
        assertTrue(sung - 10_000 in 1_000..4_000)
        val gap = lines.instrumentalGaps().single()
        assertEquals(0, gap.afterIndex)
        assertTrue(gap.endMs - gap.startMs > 20_000)
    }

    @Test
    fun aLineWithItsNextCloseByStillFillsMostOfIt() {
        val lines = LrcParser.parse("[00:10.00]Tum hi ho\n[00:12.00]Ab tum hi ho", 0L)
        assertTrue(lines.first().sungUntilMs() in 11_650L..11_700L)
    }


    @Test
    fun parsesLinesInTimeOrderWithEnds() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [00:12.50]Second line
            [00:05.00]First line
            [00:20.123]Third line
            """.trimIndent(),
            durationMs = 30_000,
        )
        assertEquals(listOf("First line", "Second line", "Third line"), lines.map { it.text })
        assertEquals(listOf(5_000L, 12_500L, 20_123L), lines.map { it.startMs })
        assertEquals(12_500L, lines[0].endMs)
        assertEquals(20_123L, lines[1].endMs)
        // The last line runs to the song's end when that comes first.
        assertEquals(30_000L, lines[2].endMs)
    }

    @Test
    fun repeatedTimestampsBecomeSeparateLines() {
        val lines = LrcParser.parse("[00:10.00][00:40.00]Chorus\n[00:20.00]Verse")
        assertEquals(listOf(10_000L, 20_000L, 40_000L), lines.map { it.startMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.text })
    }

    @Test
    fun blankLinesAreGapsNotLyrics() {
        val lines = LrcParser.parse("[00:01.00]Hello\n[00:05.00]\n[00:09.00]World")
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(5_000L, lines[0].endMs)
    }

    @Test
    fun offsetTagShiftsEarlier() {
        val lines = LrcParser.parse("[offset:500]\n[00:10.00]Line")
        assertEquals(9_500L, lines.single().startMs)
    }

    @Test
    fun enhancedWordTagsGiveExactWordTiming() {
        val line = LrcParser.parse("[00:01.00]<00:01.00>Hey <00:01.40>there <00:02.10>you\n[00:04.00]Next").first()
        assertTrue(line.wordSynced)
        assertEquals("Hey there you", line.text)
        assertEquals(listOf("Hey ", "there ", "you"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_400L, 2_100L), line.words.map { it.startMs })
        assertEquals(4_000L, line.words.last().endMs)
    }

    @Test
    fun lineSyncedWordsAreSpreadInOrderInsideTheLine() {
        val line = LrcParser.parse("[00:00.00]one two three\n[00:03.00]x").first()
        assertFalse(line.wordSynced)
        assertEquals("one two three", line.words.joinToString("") { it.text }.trim())
        line.words.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        assertTrue(line.words.last().endMs <= 3_000L)
    }

    @Test
    fun activeIndexFindsTheLineBeingSung() {
        val lines = LrcParser.parse("[00:05.00]a\n[00:10.00]b\n[00:15.00]c")
        assertEquals(-1, lines.activeIndex(4_999))
        assertEquals(0, lines.activeIndex(5_000))
        assertEquals(1, lines.activeIndex(14_999))
        assertEquals(2, lines.activeIndex(60_000))
    }

    @Test
    fun instrumentalMarkerLeavesAGapBetweenLines() {
        // The blank [00:08] marks where the singing stops before a long instrumental.
        val lines = LrcParser.parse("[00:05.00]first line\n[00:08.00]\n[00:30.00]after the solo\n[00:33.00]end")
        val gaps = lines.instrumentalGaps()
        assertEquals(1, gaps.size)
        assertEquals(0, gaps.first().afterIndex)
        assertTrue(gaps.first().startMs <= 8_000L)
        assertEquals(30_000L, gaps.first().endMs)
    }

    @Test
    fun longTailAfterAShortLineCountsAsAGap() {
        // No marker: the line runs until the next one 30 s later, but its words are
        // spread over at most 8 s, so the rest of the stretch has nothing sung.
        val lines = LrcParser.parse("[00:05.00]short line\n[00:35.00]next")
        val gaps = lines.instrumentalGaps()
        assertEquals(1, gaps.size)
        assertTrue(gaps.first().startMs <= 13_000L)
    }

    @Test
    fun ordinaryLineChangesAreNotGaps() {
        val lines = LrcParser.parse("[00:05.00]one\n[00:08.00]two\n[00:11.00]three")
        assertTrue(lines.instrumentalGaps().isEmpty())
    }
}

class TrackNameCleanerTest {

    @Test
    fun splitsArtistDashTitleUploadsAndDropsVevo() {
        val c = TrackNameCleaner.clean("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO")
        assertEquals("Blinding Lights", c.title)
        assertEquals("The Weeknd", c.artist)
    }

    @Test
    fun dropsNoiseBracketsAndFeatures() {
        val c = TrackNameCleaner.clean("Stay [Official Audio] (feat. Justin Bieber)", "The Kid LAROI")
        assertEquals("Stay", c.title)
        assertEquals("The Kid LAROI", c.artist)
    }

    @Test
    fun keepsMeaningfulParentheses() {
        val c = TrackNameCleaner.clean("Bohemian Rhapsody (Live Aid)", "Queen")
        assertEquals("Bohemian Rhapsody (Live Aid)", c.title)
    }

    @Test
    fun topicChannelsLoseTheirSuffix() {
        val c = TrackNameCleaner.clean("Tum Hi Ho", "Arijit Singh - Topic")
        assertEquals("Tum Hi Ho", c.title)
        assertEquals("Arijit Singh", c.artist)
    }

    @Test
    fun leavesADashTitleAloneWhenTheLeftSideIsNotTheArtist() {
        val c = TrackNameCleaner.clean("Intro - Reprise", "Some Band")
        assertEquals("Intro - Reprise", c.title)
        assertEquals("Some Band", c.artist)
    }

    @Test
    fun dropsReleaseNotesAfterADash() {
        assertEquals("Let It Be", TrackNameCleaner.clean("Let It Be - Remastered 2009", "The Beatles").title)
        assertEquals("Let It Be", TrackNameCleaner.clean("Let It Be - 2009 Remaster", "The Beatles").title)
        assertEquals("Shout", TrackNameCleaner.clean("Shout - Radio Edit", "Tears for Fears").title)
        assertEquals("Song", TrackNameCleaner.clean("Song (Deluxe Edition)", "Band").title)
        assertEquals("Song", TrackNameCleaner.clean("Song [Bonus Track]", "Band").title)
    }

    @Test
    fun usesTheFirstCreditedArtist() {
        val c = TrackNameCleaner.clean("Song", "Alpha, Beta & Gamma")
        assertEquals("Alpha", c.artist)
    }
}
