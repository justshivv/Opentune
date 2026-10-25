package com.opentune.data.lyrics

import com.opentune.data.settings.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules lyrics pass through before they're shown, with made-up lines. */
class LyricsQualityTest {
    private fun line(start: Long, text: String, end: Long = 0) = LyricLine(start, end, text, emptyList(), wordSynced = false)
    private fun worded(start: Long, end: Long, vararg words: Pair<String, Long>) = LyricLine(
        start, end, words.joinToString("") { it.first },
        words.map { (w, at) -> LyricWord(w, at, 0) }, wordSynced = true,
    )
    private fun synced(vararg lines: LyricLine, recordingMs: Long = 0) = Lyrics.Synced(lines.toList(), "Test", recordingMs = recordingMs)

    /** Twelve made-up lines, a few seconds apart, each different enough to match on. */
    private val verse = listOf(
        "paper boats along the river", "a lantern hums above the door", "we count the trains that never stop",
        "the kettle sings a quiet tune", "windows fog with morning breath", "our shadows lean against the wall",
        "a radio plays somewhere far", "the garden waits for summer rain", "pockets full of borrowed light",
        "the stairs remember every step", "a clock forgets to tell the hour", "and still the river carries on",
    )
    private fun timedVerse(shift: Long = 0, recordingMs: Long = 0) =
        synced(*verse.mapIndexed { i, t -> line(10_000L + i * 4_000L + shift, t) }.toTypedArray(), recordingMs = recordingMs)

    // ---- Tidying ----

    @Test fun gapMarkersAndPartNamesAreTakenOut() {
        assertTrue(LyricsQuality.isCue("♪"))
        assertTrue(LyricsQuality.isCue("..."))
        assertTrue(LyricsQuality.isCue("[Chorus]"))
        assertTrue(LyricsQuality.isCue("(Instrumental)"))
        assertFalse(LyricsQuality.isCue("(hold me closer)"))
        val tidied = LyricsQuality.tidy(listOf(line(0, "♪"), line(1_000, "paper boats"), line(5_000, "[Bridge]"), line(6_000, "a lantern hums")), 60_000)
        assertEquals(listOf("paper boats", "a lantern hums"), tidied.map { it.text })
    }

    @Test fun aLineWrittenDownTwiceIsKeptOnce() {
        val tidied = LyricsQuality.tidy(listOf(line(1_000, "paper boats"), line(1_010, "Paper boats!"), line(4_000, "a lantern hums")), 60_000)
        assertEquals(2, tidied.size)
    }

    @Test fun aSecondTextAtTheSameMomentGoesUnderTheLine() {
        val tidied = LyricsQuality.tidy(listOf(line(1_000, "paper boats"), line(1_000, "barcos de papel"), line(4_000, "next")), 60_000)
        assertEquals("paper boats", tidied[0].text)
        assertEquals("barcos de papel", tidied[0].background)
    }

    @Test fun aLineEndsWhereTheNextOneStarts() {
        val tidied = LyricsQuality.tidy(listOf(line(1_000, "paper boats", end = 9_000), line(4_000, "a lantern hums")), 60_000)
        assertEquals(4_000L, tidied[0].endMs)
        // The last one, with no end, gets a few seconds rather than running on.
        assertTrue(tidied[1].endMs in 4_001L..9_000L)
    }

    @Test fun lyricsAreSortedIntoTimeOrder() {
        val tidied = LyricsQuality.tidy(listOf(line(8_000, "third"), line(1_000, "first"), line(4_000, "second")), 60_000)
        assertEquals(listOf("first", "second", "third"), tidied.map { it.text })
    }

    @Test fun wordsWithNoEndEndWhereTheNextWordStarts() {
        val filled = LyricsQuality.fillWordEnds(listOf(LyricWord("paper ", 1_000, 0), LyricWord("boats", 1_400, 0)), lineEnd = 2_200)
        assertEquals(1_400L, filled[0].endMs)
        assertEquals(2_200L, filled[1].endMs)
        // Never longer than a couple of seconds, however long the line runs.
        val long = LyricsQuality.fillWordEnds(listOf(LyricWord("hold", 1_000, 0)), lineEnd = 30_000)
        assertTrue(long[0].endMs <= 3_000L)
    }

    @Test fun plainTextLosesStrayTimestamps() {
        assertEquals("paper boats\n\na lantern hums", LyricsQuality.tidyPlain("[00:12.34]paper boats\n\n\n\n<00:15.00>a lantern hums"))
    }

    // ---- Checks ----

    @Test fun lyricsTimedForAnotherVersionAreLeftOut() {
        assertNotNull(LyricsQuality.problem(timedVerse(recordingMs = 240_000), 200_000))
        assertNull(LyricsQuality.problem(timedVerse(recordingMs = 203_000), 200_000))
    }

    @Test fun lyricsRunningPastTheEndOrStartingTooLateAreLeftOut() {
        assertNotNull(LyricsQuality.problem(timedVerse(), 40_000))
        assertNotNull(LyricsQuality.problem(synced(line(150_000, "too late"), line(152_000, "for this song")), 200_000))
        assertNull(LyricsQuality.problem(timedVerse(), 200_000))
        // Without the track's length nothing can be checked against it.
        assertNull(LyricsQuality.problem(timedVerse(recordingMs = 999_000), 0))
    }

    @Test fun textSayingThereAreNoLyricsIsLeftOut() {
        assertTrue(LyricsQuality.isPlaceholder("Instrumental"))
        assertTrue(LyricsQuality.isPlaceholder("No lyrics available."))
        assertTrue(LyricsQuality.isPlaceholder("纯音乐，请欣赏"))
        assertFalse(LyricsQuality.isPlaceholder("paper boats along the river"))
        assertNotNull(LyricsQuality.problem(Lyrics.Plain("Instrumental", "Test"), 200_000))
    }

    @Test fun linesAllAtOneMomentAreNotReallyTimed() {
        assertTrue(LyricsQuality.untimed(synced(*verse.take(6).map { line(0, it) }.toTypedArray())))
        assertFalse(LyricsQuality.untimed(timedVerse()))
    }

    // ---- Choosing ----

    private fun candidate(order: Int, lyrics: Lyrics, source: LyricsSource = LyricsSource.LRCLIB) = LyricsQuality.Candidate(order, source, lyrics)

    @Test fun wordByWordBeatsLineByLineBeatsPlainWhateverTheOrder() {
        val plain = candidate(0, Lyrics.Plain(verse.joinToString("\n"), "A"))
        val lines = candidate(1, timedVerse())
        val words = candidate(2, synced(worded(10_000, 13_000, "paper " to 10_000, "boats" to 11_000)))
        assertEquals(2, LyricsQuality.choose(listOf(plain, lines, words), LyricsRepository.WORD_SYNCED)?.order)
        assertEquals(1, LyricsQuality.choose(listOf(plain, lines), LyricsRepository.WORD_SYNCED)?.order)
        // With word sync not wanted, line-synced answers earlier in the order still win.
        assertEquals(1, LyricsQuality.choose(listOf(plain, lines, words), LyricsRepository.LINE_SYNCED)?.order)
    }

    @Test fun anAnswerTheOtherSourcesDontShareIsAnotherSong() {
        val other = synced(*listOf(
            "copper wires across the valley", "a falcon circles over town", "seven doors and seven keys",
            "the harbour lights are going out", "nobody waits beside the gate", "thunder rolls behind the hill",
            "a lonely engine starts to climb", "the market sleeps beneath the snow", "letters lost in winter mail",
            "the orchard bends toward the sea", "a candle burns for someone gone", "and slowly morning finds the shore",
        ).mapIndexed { i, t -> line(10_000L + i * 4_000L, t) }.toTypedArray())
        val wrong = candidate(0, other)
        val a = candidate(1, timedVerse())
        val b = candidate(2, timedVerse(shift = 200))
        assertTrue(LyricsQuality.outlier(wrong, listOf(wrong, a, b)))
        assertEquals(1, LyricsQuality.choose(listOf(wrong, a, b), LyricsRepository.LINE_SYNCED)?.order)
        // Alone, there's nothing to compare it with, and it stays.
        assertFalse(LyricsQuality.outlier(wrong, listOf(wrong)))
    }

    @Test fun anEnglishTranslationOfLyricsInAnotherScriptIsLeftOut() {
        fun lines(vararg t: String) = synced(*t.mapIndexed { i, s -> line(10_000L + i * 4_000L, s) }.toTypedArray())
        val english = lines(
            "I am tired of the same things every day", "you know that I am the one for you", "make it feel good like it is amazing",
            "I don't really want the basics now", "tell me can you show me what you got", "if you can do that it's cool with me",
            "all the lights are on for you tonight", "never let it go when it is love",
        )
        val gurmukhi = lines(
            "ਮੈਂ ਥੱਕ ਗਿਆ ਹਾਂ ਹਰ ਰੋਜ਼ ਇੱਕੋ ਗੱਲ ਤੋਂ", "ਤੈਨੂੰ ਪਤਾ ਹੈ ਮੈਂ ਹੀ ਹਾਂ ਉਹ", "ਇਸ ਨੂੰ ਚੰਗਾ ਮਹਿਸੂਸ ਕਰਾ ਦੇ ਯਾਰਾ",
            "ਮੈਨੂੰ ਸਧਾਰਨ ਗੱਲਾਂ ਨਹੀਂ ਚਾਹੀਦੀਆਂ", "ਦੱਸ ਤੂੰ ਕੀ ਕਰ ਸਕਦਾ ਹੈਂ", "ਜੇ ਤੂੰ ਇਹ ਕਰ ਸਕਦਾ ਤਾਂ ਠੀਕ ਹੈ",
        )
        val romanized = lines(
            "main thakk gaya haan har roz ikko gall ton", "tainu pata hai main hi haan oh", "is nu changa mehsoos kara de yaara",
            "mainu sadharan gallan nahin chahidiyan", "dass tu ki kar sakda hain", "je tu eh kar sakda taan theek hai",
        )
        val captions = candidate(9, english, LyricsSource.YOUTUBE_CAPTIONS)
        val source = candidate(1, gurmukhi)
        // Captions in English beside the song's own Gurmukhi words: a translation.
        assertTrue(LyricsQuality.outlier(captions, listOf(captions, source)))
        assertEquals(1, LyricsQuality.choose(listOf(captions, source), LyricsRepository.LINE_SYNCED)?.order)
        // Any source: English where two others agree on the words in another script.
        val lrclib = candidate(0, english)
        assertTrue(LyricsQuality.outlier(lrclib, listOf(lrclib, source, candidate(2, gurmukhi))))
        assertFalse(LyricsQuality.outlier(lrclib, listOf(lrclib, source)))
        // Romanized lyrics are the song's words in Latin letters, not English: kept.
        val latin = candidate(3, romanized, LyricsSource.YOUTUBE_CAPTIONS)
        assertFalse(LyricsQuality.outlier(latin, listOf(latin, source)))
        // English captions with nothing to compare against stay.
        assertFalse(LyricsQuality.outlier(captions, listOf(captions)))
    }

    // ---- Timing ----

    @Test fun aSteadyShiftBetweenTwoTimingsIsFound() {
        assertEquals(1_500L, LyricsQuality.shiftBetween(timedVerse(), timedVerse(shift = 1_500)))
        assertEquals(0L, LyricsQuality.shiftBetween(timedVerse(), timedVerse()))
    }

    @Test fun timingsThatDontLineUpGiveNoShift() {
        val scattered = synced(*verse.mapIndexed { i, t -> line(10_000L + i * 4_000L + (if (i % 2 == 0) 2_000 else -1_500), t) }.toTypedArray())
        assertNull(LyricsQuality.shiftBetween(timedVerse(), scattered))
    }

    @Test fun lyricsRunningEarlyAreMovedWhenSourcesTimedToThisTrackAgree() {
        val duration = 200_000L
        val chosen = timedVerse()
        val reference = timedVerse(shift = 2_000, recordingMs = 200_500)
        // The lines come 2 s later in the reference: the clock has to run 2 s behind.
        assertEquals(-2_000L, LyricsQuality.autoOffset(chosen, listOf(reference), duration))
        // A source timed to another cut doesn't count.
        assertEquals(0L, LyricsQuality.autoOffset(chosen, listOf(timedVerse(shift = 2_000, recordingMs = 230_000)), duration))
        // A small difference is the usual disagreement between sources.
        assertEquals(0L, LyricsQuality.autoOffset(chosen, listOf(timedVerse(shift = 300, recordingMs = 200_500)), duration))
    }

    @Test fun lyricsAlreadyTimedToThisTrackNeedTwoSourcesToMove() {
        val duration = 200_000L
        val chosen = timedVerse(recordingMs = 200_000)
        val one = timedVerse(shift = 2_000, recordingMs = 200_500)
        assertEquals(0L, LyricsQuality.autoOffset(chosen, listOf(one), duration))
        // Two agreeing: moved by the middle of what they say.
        assertEquals(-2_050L, LyricsQuality.autoOffset(chosen, listOf(one, timedVerse(shift = 2_100, recordingMs = 199_800)), duration))
    }

    /** The verse word by word, a word every 600 ms, each given the next of [lengths]. */
    private fun wordVerse(shift: Long = 0, recordingMs: Long = 0, lengths: List<Long> = listOf(300, 520, 410)) = synced(
        *verse.mapIndexed { i, t ->
            val start = 10_000L + i * 4_000L + shift
            val parts = t.split(' ')
            var at = start
            val words = parts.mapIndexed { j, w -> LyricWord(if (j < parts.lastIndex) "$w " else w, at, at + lengths[j % lengths.size]).also { at += 600 } }
            LyricLine(start, at + 400, words.joinToString("") { it.text }, words, wordSynced = true)
        }.toTypedArray(),
        recordingMs = recordingMs,
    )

    @Test fun wordTimingThatHoldsUpIsTrusted() {
        val duration = 200_000L
        assertTrue(LyricsQuality.wordsTrusted(wordVerse(recordingMs = 200_000), emptyList(), duration))
        // Another source a steady 1.2 s later still agrees on how the lines fall.
        assertTrue(LyricsQuality.wordsTrusted(wordVerse(recordingMs = 200_000), listOf(timedVerse(shift = 1_200, recordingMs = 200_300)), duration))
    }

    @Test fun wordTimingIsNotTrustedForANearlyMatchingCut() {
        // Five seconds longer: close enough to pass as this recording, too far for the words.
        assertFalse(LyricsQuality.wordsTrusted(wordVerse(recordingMs = 205_000), emptyList(), 200_000))
    }

    @Test fun wordsSpreadEvenlyAreNotReallyTimed() {
        assertFalse(LyricsQuality.wordsTrusted(wordVerse(lengths = listOf(400)), emptyList(), 200_000))
    }

    @Test fun wordTimingIsNotTrustedWhenTheOtherSourcesPlaceTheLinesUnevenly() {
        val scattered = synced(
            *verse.mapIndexed { i, t -> line(10_000L + i * 4_000L + (if (i % 2 == 0) 1_400 else -900), t) }.toTypedArray(),
            recordingMs = 200_200,
        )
        assertFalse(LyricsQuality.wordsTrusted(wordVerse(recordingMs = 200_000), listOf(scattered), 200_000))
        // One source agreeing is enough.
        assertTrue(LyricsQuality.wordsTrusted(wordVerse(recordingMs = 200_000), listOf(scattered, timedVerse(recordingMs = 200_100)), 200_000))
    }

    @Test fun untrustedWordTimingIsLitALineAtATime() {
        val lines = LyricsQuality.asLines(wordVerse()).lines
        assertTrue(lines.none { it.wordSynced })
        assertEquals(10_000L, lines.first().startMs)
        assertEquals(LyricsRepository.LINE_SYNCED, LyricsRepository.rank(LyricsQuality.asLines(wordVerse())))
    }

    @Test fun theListenersOwnOffsetWinsOverTheAutomaticOne() {
        val moved = timedVerse().copy(autoOffsetMs = -2_000)
        assertEquals(-2_000L, lyricsOffset(null, moved))
        assertEquals(500L, lyricsOffset(500, moved))
        // A 0 they chose on purpose turns the automatic one off.
        assertEquals(0L, lyricsOffset(0, moved))
    }

    // ---- Names ----

    @Test fun titlesMatchLooselyAndOtherSongsDont() {
        assertTrue(LyricsQuality.sameTitle("Paper Boats (Remastered 2011)", "Paper Boats"))
        assertTrue(LyricsQuality.sameTitle("Paper Boats feat. Someone", "paper boats"))
        assertTrue(LyricsQuality.sameTitle("Café Lights", "Cafe Lights"))
        assertFalse(LyricsQuality.sameTitle("Paper Boats", "Copper Wires"))
        // Different scripts can't be compared, so they pass.
        assertTrue(LyricsQuality.sameTitle("Paper Boats", "纸船"))
    }

    @Test fun anyOfTheCreditedArtistsCanMatch() {
        assertTrue(LyricsQuality.sameArtist("River Choir", "The Lanterns & River Choir"))
        assertTrue(LyricsQuality.sameArtist("River Choir", "River Choir feat. Someone"))
        assertFalse(LyricsQuality.sameArtist("River Choir", "Copper Band"))
    }

    @Test fun htmlEntitiesAreReadAsTheCharacters() {
        // As some sources sent them (BABYMONSTER, Kobo Kanaeru, BLUE ENCOUNT).
        assertEquals("No matter what I'm gonna", LyricsQuality.unescape("No matter what I&#x27;m gonna"))
        assertEquals("Apa pantas 'tuk", LyricsQuality.unescape("Apa pantas &#x27;tuk"))
        assertEquals("\"強さ\"は", LyricsQuality.unescape("&quot;強さ&quot;は"))
        assertEquals("Rock & roll <3", LyricsQuality.unescape("Rock &amp; roll &lt;3"))
        assertEquals("It's", LyricsQuality.unescape("It&#39;s"))
        // Not an entity: left as written.
        assertEquals("Tom & Jerry; &unknownthing;", LyricsQuality.unescape("Tom & Jerry; &unknownthing;"))
        val line = LyricLine(0, 1000, "You&#x27;ll", listOf(LyricWord("You&#x27;ll", 0, 1000)), wordSynced = true)
        val tidied = LyricsQuality.unescaped(Lyrics.Synced(listOf(line), "test")) as Lyrics.Synced
        assertEquals("You'll", tidied.lines[0].text)
        assertEquals("You'll", tidied.lines[0].words[0].text)
    }
}
