package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPartsTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun line(at: Long, text: String) = LyricLine(at, at + 3_000, text, emptyList(), wordSynced = false)

    @Test fun findsTheChorusFromTheLinesThatComeBack() {
        // This copy of Blinding Lights has its parts unnamed, so the chorus is worked out.
        val lyrics = LyricsPlus.parse(resource("lyricsplus-blinding-lights.json"), 200_000) as Lyrics.Synced
        assertEquals(listOf(60_964L, 117_106L, 150_712L, 184_342L), SongParts.inferred(lyrics.lines))
        // Its parts are still read.
        assertEquals(9, lyrics.parts.size)
    }

    @Test fun namedChorusesWin() {
        val lyrics = Lyrics.Synced(
            listOf(line(1_000, "one")), "test",
            listOf(SongPart("Verse", 1_000, 20_000), SongPart("PreChorus", 20_000, 30_000), SongPart("Chorus", 30_000, 50_000), SongPart("Chorus", 90_000, 110_000)),
        )
        assertEquals(listOf(30_000L, 90_000L), SongParts.choruses(lyrics))
    }

    @Test fun theNextChorusIsOfferedOnlyBeforeIt() {
        val starts = listOf(60_000L, 120_000L)
        assertEquals(60_000L, SongParts.nextChorus(starts, 10_000))
        // Playing one now: nothing to skip to.
        assertNull(SongParts.nextChorus(starts, 65_000))
        assertEquals(120_000L, SongParts.nextChorus(starts, 90_000))
        // About to start anyway.
        assertNull(SongParts.nextChorus(starts, 118_500))
        assertNull(SongParts.nextChorus(starts, 150_000))
    }

    @Test fun noRepeatsNoChorus() {
        assertTrue(SongParts.inferred(listOf(line(0, "first line here"), line(4_000, "second line here"))).isEmpty())
    }
}
