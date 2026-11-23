package com.opentune.data.lyrics

import com.opentune.data.sponsorblock.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsGapsTest {
    // Believer's music video: a scene with no music at 2:49, and credits at the end.
    private val video = 216_561L
    private val segments = listOf(
        SponsorBlock.Segment(169_060, 179_149, "music_offtopic", "gap", video),
        SponsorBlock.Segment(209_484, 216_411, "intro", "intro", video),
        SponsorBlock.Segment(212_900, 216_561, "music_offtopic", "end", 0),
    )
    private val gaps = LyricsGaps.of(segments, video)

    @Test fun onlyTheStretchesWithoutTheSongCount() {
        assertEquals(listOf(LyricsGaps.Gap(169_060, 179_149), LyricsGaps.Gap(212_900, 216_561)), gaps)
    }

    @Test fun theSongIsTheVideoWithoutThem() {
        assertEquals(202_811L, LyricsGaps.songMs(video, gaps))
    }

    @Test fun timesAfterAGapMovePastIt() {
        assertEquals(60_000L, LyricsGaps.toVideo(60_000, gaps))
        assertEquals(179_149L, LyricsGaps.toVideo(169_060, gaps))
        assertEquals(190_089L, LyricsGaps.toVideo(180_000, gaps))
    }

    @Test fun aLineSungUpToTheGapEndsThere() {
        val lyrics = Lyrics.Synced(
            listOf(
                LyricLine(160_000, 169_060, "before", listOf(LyricWord("before", 160_000, 169_060)), true),
                LyricLine(169_060, 175_000, "after", listOf(LyricWord("after", 169_060, 175_000)), true),
            ),
            "test",
        )
        val moved = LyricsGaps.onto(lyrics, gaps) as Lyrics.Synced
        assertEquals(listOf(160_000L to 169_060L, 179_149L to 185_089L), moved.lines.map { it.startMs to it.endMs })
        assertEquals(179_149L, moved.lines[1].words[0].startMs)
    }

    @Test fun overlappingMarksCountOnce() {
        val twice = listOf(
            SponsorBlock.Segment(10_000, 20_000, "music_offtopic", "a", 0),
            SponsorBlock.Segment(15_000, 25_000, "music_offtopic", "b", 0),
        )
        assertEquals(listOf(LyricsGaps.Gap(10_000, 25_000)), LyricsGaps.of(twice, 100_000))
    }
}
