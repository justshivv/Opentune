package com.opentune.data.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockTest {
    @Test fun hashPrefixIsTheFirstFourHexDigitsOfSha256() {
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("dQw4w9WgXcQ".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4), SponsorBlock.hashPrefix("dQw4w9WgXcQ"))
        assertEquals(4, SponsorBlock.hashPrefix("abc").length)
    }

    @Test fun picksThePlayingVideoOutOfAPrefixAnswer() {
        val body = """
            [
              {"videoID":"otherVideo1","segments":[{"segment":[1.0,2.0],"category":"sponsor","actionType":"skip","UUID":"x"}]},
              {"videoID":"wantedVideo","segments":[
                {"segment":[200.5,231.0],"category":"music_offtopic","actionType":"skip","UUID":"b","videoDuration":240.2},
                {"segment":[0,12.25],"category":"music_offtopic","actionType":"skip","UUID":"a","videoDuration":240.2},
                {"segment":[50,60],"category":"sponsor","actionType":"mute","UUID":"c"},
                {"segment":[70,65],"category":"sponsor","actionType":"skip","UUID":"d"}
              ]}
            ]
        """
        val segments = SponsorBlock.parse(body, "wantedVideo")
        assertEquals(listOf("a", "b"), segments.map { it.uuid })
        assertEquals(0L, segments[0].startMs)
        assertEquals(12_250L, segments[0].endMs)
        assertEquals(200_500L, segments[1].startMs)
        assertEquals(240_200L, segments[1].videoDurationMs)
    }

    @Test fun nothingForAnUnlistedVideoOrBadBody() {
        assertTrue(SponsorBlock.parse("""[{"videoID":"x","segments":[]}]""", "y").isEmpty())
        assertTrue(SponsorBlock.parse("not json", "y").isEmpty())
    }

    @Test fun segmentsOnlyApplyToTheSameCut() {
        val s = SponsorBlock.Segment(0, 10_000, "music_offtopic", "a", videoDurationMs = 240_000)
        assertTrue(SponsorBlock.fits(s, 241_500))
        assertFalse(SponsorBlock.fits(s, 200_000))
        assertTrue(SponsorBlock.fits(s.copy(videoDurationMs = 0), 200_000))
    }

    private val gap = SponsorBlock.Segment(169_060, 179_149, "music_offtopic", "gap", 216_561)
    private val end = SponsorBlock.Segment(212_900, 216_561, "music_offtopic", "end", 216_561)

    @Test fun aSegmentIsSkippedAgainWhenTheSongPlaysFromTheTop() {
        assertEquals(emptySet<String>(), SponsorBlock.stillSkipped(setOf("gap", "end"), listOf(gap, end), atMs = 1_000))
    }

    @Test fun seekingIntoASkippedSegmentPlaysIt() {
        assertEquals(setOf("gap"), SponsorBlock.stillSkipped(setOf("gap"), listOf(gap, end), atMs = 172_000))
        assertEquals(setOf("gap"), SponsorBlock.stillSkipped(setOf("gap"), listOf(gap, end), atMs = 190_000))
    }

    @Test fun onlyTheSegmentsAheadAreSkippedAgain() {
        assertEquals(setOf("gap"), SponsorBlock.stillSkipped(setOf("gap", "end"), listOf(gap, end), atMs = 200_000))
    }
}
