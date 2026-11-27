package com.opentune.data.lyrics

import com.opentune.data.sponsorblock.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playhead of a music video as lyric time, with the stretches that aren't the song
 * known: an invented upload with a talking intro and a skit halfway through. Lyrics are
 * timed to the song; the player's position runs on the video's clock.
 */
class LyricsPlayheadTest {
    private val videoMs = 230_000L
    private val gaps = LyricsGaps.of(
        listOf(
            SponsorBlock.Segment(0, 15_000, "music_offtopic", "intro", videoMs),
            SponsorBlock.Segment(100_000, 110_000, "music_offtopic", "skit", videoMs),
        ),
        videoMs,
    )

    private fun line(start: Long, end: Long, text: String): LyricLine {
        val words = text.split(' ')
        val step = (end - start) / words.size
        return LyricLine(start, end, text, words.mapIndexed { i, w -> LyricWord(if (i < words.lastIndex) "$w " else w, start + i * step, start + (i + 1) * step) }, wordSynced = true)
    }

    // On the song's own clock, as every lyrics source times them.
    private val song = Lyrics.Synced(
        listOf(
            line(5_000, 15_000, "a lighthouse counts the waves"),
            line(20_000, 40_000, "the harbour hums in amber"),
            line(80_000, 85_000, "and the gulls go quiet"),
            line(86_000, 95_000, "till the ferry bell returns"),
            line(120_000, 130_000, "we fold the evening into sails"),
        ),
        "Test",
    )
    private val placed = LyricsGaps.onto(song, gaps) as Lyrics.Synced

    private fun shownAt(playheadMs: Long, offsetMs: Long = 0) = placed.lines.momentAt(playheadMs + offsetMs).current?.text

    @Test fun thePlayheadLosesEveryGapBeforeItAndNoneAfter() {
        // In the intro the song hasn't started.
        assertEquals(0L, LyricsGaps.toSong(9_000, gaps))
        // Past the intro, only the intro comes off: the skit is still ahead.
        assertEquals(35_000L, LyricsGaps.toSong(50_000, gaps))
        // In the skit, the song waits where it stopped.
        assertEquals(85_000L, LyricsGaps.toSong(105_000, gaps))
        // After both, both come off.
        assertEquals(95_000L, LyricsGaps.toSong(120_000, gaps))
    }

    @Test fun songTimeAndVideoTimeTurnIntoEachOther() {
        for (songMs in listOf(0L, 5_000L, 84_999L, 85_000L, 86_000L, 130_000L)) {
            assertEquals(songMs, LyricsGaps.toSong(LyricsGaps.toVideo(songMs, gaps), gaps))
        }
        for (videoMs in listOf(15_000L, 50_000L, 99_999L, 110_000L, 200_000L)) {
            assertEquals(videoMs, LyricsGaps.toVideo(LyricsGaps.toSong(videoMs, gaps), gaps))
        }
    }

    @Test fun placedLyricsSingTheLineTheSongClockSaysAtEveryPlayheadOutsideTheGaps() {
        fun sung(lines: List<LyricLine>, ms: Long) = lines.getOrNull(lines.activeIndex(ms))?.text
        var at = 15_000L
        while (at < videoMs) {
            if (gaps.none { at >= it.startMs && at < it.endMs }) {
                assertEquals("at $at", sung(song.lines, LyricsGaps.toSong(at, gaps)), sung(placed.lines, at))
            }
            at += 250
        }
    }

    @Test fun withSkippingOffTheIntroAndTheSkitPlayAndTheLyricsWait() {
        // The talking intro plays: nothing is sung yet, and the first line is next.
        assertNull(shownAt(10_000))
        assertEquals("a lighthouse counts the waves", placed.lines.momentAt(10_000).next?.text)
        assertEquals("a lighthouse counts the waves", shownAt(20_500))
        // The line sung up to the skit stops there; the next waits for the song to come back.
        assertEquals("and the gulls go quiet", shownAt(99_500))
        for (inSkit in 100_000L until 110_000L step 500) assertNotEquals("till the ferry bell returns", shownAt(inSkit))
        assertEquals("till the ferry bell returns", shownAt(111_200))
    }

    @Test fun withSkippingOnThePlayerJumpsAndTheLyricsCarryOnFromTheSamePlace() {
        // The player starts past the intro: the first line is still five seconds off.
        assertNull(shownAt(15_000))
        assertEquals("a lighthouse counts the waves", shownAt(20_000))
        // At the skit the player jumps to its end; the song picks up exactly where it was.
        assertEquals("and the gulls go quiet", shownAt(99_900))
        assertEquals(LyricsGaps.toSong(99_900, gaps) + 100, LyricsGaps.toSong(110_000, gaps))
        assertEquals("till the ferry bell returns", shownAt(111_000))
        assertEquals("we fold the evening into sails", shownAt(145_000))
    }

    @Test fun aTappedLineSeeksTheVideoToWhereThatLineIsSung() {
        for (songLine in song.lines) {
            val shown = placed.lines.first { it.text == songLine.text }
            // What the player seeks to: the shown line's start, on the video's clock.
            val seekTo = shown.startMs
            assertEquals(LyricsGaps.toVideo(songLine.startMs, gaps), seekTo)
            assertEquals(songLine.startMs, LyricsGaps.toSong(seekTo, gaps))
            assertEquals(songLine.text, shownAt(seekTo))
        }
    }

    @Test fun theSongsOwnLyricsOffsetStillAppliesOnTop() {
        // Lyrics moved 700 ms later for this song: the player seeks to the line less the offset.
        val offsetMs = -700L
        val shown = placed.lines.first { it.text == "till the ferry bell returns" }
        val seekTo = (shown.startMs - offsetMs).coerceAtLeast(0)
        assertEquals(111_700L, seekTo)
        assertEquals("till the ferry bell returns", shownAt(seekTo, offsetMs))
        assertNotEquals("till the ferry bell returns", shownAt(seekTo - 100, offsetMs))
    }

    @Test fun captionsTimedToTheVideoComeOntoTheSongsClockWithoutTheTalking() {
        val captions = Lyrics.Synced(
            listOf(
                line(3_000, 6_000, "hello and welcome back"),
                line(20_000, 30_000, "a lighthouse counts the waves"),
                line(104_000, 106_000, "did you hear that"),
                line(111_000, 120_000, "till the ferry bell returns"),
            ),
            "YouTube captions",
            recordingMs = 205_000,
        )
        val onSong = LyricsGaps.fromVideo(captions, gaps) as Lyrics.Synced
        assertEquals(listOf("a lighthouse counts the waves", "till the ferry bell returns"), onSong.lines.map { it.text })
        assertEquals(listOf(5_000L, 86_000L), onSong.lines.map { it.startMs })
        assertTrue(onSong.lines.all { l -> l.words.all { it.startMs >= l.startMs } })
        assertEquals(205_000L, onSong.recordingMs)
        // Placed for the player again, they're back where the video has them.
        assertEquals(listOf(20_000L, 111_000L), (LyricsGaps.onto(onSong, gaps) as Lyrics.Synced).lines.map { it.startMs })
    }

    @Test fun withNoStretchesKnownNothingMoves() {
        assertEquals(song, LyricsGaps.onto(song, emptyList()))
        assertEquals(song, LyricsGaps.fromVideo(song, emptyList()))
        assertEquals(42_000L, LyricsGaps.toSong(42_000, emptyList()))
    }
}
