package com.opentune.data.lyrics

import com.opentune.data.settings.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word-by-word lyrics where any source has them: Portato's QRC, LyricsPlus' syllables, and the choice. */
class WordSyncTest {
    // An invented song, as Better Lyrics Portato answers for it: QQ's QRC in a <QrcInfos>
    // document, a title line and credits first, quotes left unescaped in the credits.
    private val qrc = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
<QrcHeadInfo SaveTime="12" Version="100"/>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1" LyricContent="[ti:Paper Lanterns]
[ar:The Quiet Harbour]
[offset:0]
[0,3000]Paper(0,500) (500,100)Lanterns(600,700) (1300,100)-(1400,100) (1500,100)The(1600,300) (1900,100)Quiet(2000,400) (2400,100)Harbour(2500,500)
[3000,2000]Lyrics(3000,200) (3200,100)by：(3300,300)Mira(3600,200) (3800,100)"Moth"(3900,300) (4200,100)Vale(4300,300)
[5000,2000]Composed(5000,200) (5200,100)by：(5300,300)Ivo(5600,200) (5800,100)Brand(5900,300)
[12000,3400]We (12000,300)fold (12300,400)the (12700,200)evening (12900,900)into (13800,300)sails(14100,800)
[15600,2900]And (15600,250)let (15850,300)the (16150,200)harbour (16350,700)go(17050,900)
"/>
</LyricInfo>
</QrcInfos>"""

    private fun portatoBody(lyrics: String) = """{"lyrics":${kotlinx.serialization.json.JsonPrimitive(lyrics)},"provider":"qq"}"""

    @Test fun portatoReadsQqWordTimingAndLeavesTheCreditsOut() {
        val lyrics = BetterLyrics.parsePortato(portatoBody(qrc), 180_000, "Paper Lanterns", "The Quiet Harbour") as Lyrics.Synced
        assertEquals(BetterLyrics.PORTATO_SOURCE, lyrics.source)
        assertEquals(listOf("We fold the evening into sails", "And let the harbour go"), lyrics.lines.map { it.text })
        val first = lyrics.lines.first()
        assertTrue(first.wordSynced)
        assertEquals(12_000L, first.startMs)
        assertEquals(15_400L, first.endMs)
        assertEquals(listOf("We ", "fold ", "the ", "evening ", "into ", "sails"), first.words.map { it.text })
        assertEquals(listOf(12_000L, 12_300L, 12_700L, 12_900L, 13_800L, 14_100L), first.words.map { it.startMs })
        assertEquals(14_900L, first.words.last().endMs)
        assertEquals(LyricsRepository.WORD_SYNCED, LyricsRepository.rank(lyrics))
    }

    @Test fun qrcSpacesTimedAsTheirOwnPiecesJoinTheWordBefore() {
        val lines = Qrc.parse("[1000,1500]Night(1000,400) (1400,100)tide(1500,500)\n")
        assertEquals("Night tide", lines.single().text)
        assertEquals(listOf("Night ", "tide"), lines.single().words.map { it.text })
    }

    @Test fun stampsBeforeTheirWordsAreReadToo() {
        // NetEase's YRC: each stamp before its word, a third number after the length.
        val lines = Qrc.parse("[2000,1800](2000,400,0)Salt (2400,600,0)and (3000,800,0)cedar")
        val line = lines.single()
        assertEquals("Salt and cedar", line.text)
        assertEquals(listOf(2_000L, 2_400L, 3_000L), line.words.map { it.startMs })
        assertTrue(line.wordSynced)
    }

    @Test fun anOffsetTagMovesEveryWord() {
        val line = Qrc.parse("[offset:500]\n[2000,1000]Low(2000,400)tide(2400,600)").single()
        assertEquals(1_500L, line.startMs)
        assertEquals(listOf(1_500L, 1_900L), line.words.map { it.startMs })
    }

    @Test fun aPortatoMissIsNothing() {
        assertNull(BetterLyrics.parsePortato("""{"error":"not found"}""", 180_000, "Paper Lanterns", "The Quiet Harbour"))
        assertNull(BetterLyrics.parsePortato("not json", 180_000, "Paper Lanterns", "The Quiet Harbour"))
    }

    // ---- LyricsPlus ----

    private fun lyricsPlus(type: String?) = """{${type?.let { "\"type\":\"$it\"," } ?: ""}"metadata":{"title":"Paper Lanterns"},
        "lyrics":[{"time":12000,"duration":3400,"text":"We fold the evening",
        "syllabus":[{"time":12000,"duration":300,"text":"We "},{"time":12300,"duration":400,"text":"fold "},
        {"time":12700,"duration":200,"text":"the "},{"time":12900,"duration":900,"text":"evening"}]}]}"""

    @Test fun lyricsPlusSyllablesAreWordsUnlessTheAnswerSaysLines() {
        for (type in listOf("Word", "Syllable", null)) {
            val line = (LyricsPlus.parse(lyricsPlus(type), 180_000, "Paper Lanterns") as Lyrics.Synced).lines.single()
            assertTrue("type $type", line.wordSynced)
            assertEquals(12_300L, line.words[1].startMs)
        }
        assertFalse((LyricsPlus.parse(lyricsPlus("Line"), 180_000, "Paper Lanterns") as Lyrics.Synced).lines.single().wordSynced)
    }

    // ---- Choosing ----

    @Test fun wordsFromASourceFurtherDownBeatLinesFromOneAbove() {
        val lines = Lyrics.Synced(LrcParser.parse("[00:12.00]We fold the evening into sails\n[00:15.60]And let the harbour go"), "LRCLIB", recordingMs = 180_000)
        val words = BetterLyrics.parsePortato(portatoBody(qrc), 180_000, "Paper Lanterns", "The Quiet Harbour")!!
        val candidates = listOf(
            LyricsQuality.Candidate(2, LyricsSource.LRCLIB, lines),
            LyricsQuality.Candidate(6, LyricsSource.BETTERLYRICS_PORTATO, words),
        )
        assertEquals(LyricsSource.BETTERLYRICS_PORTATO, LyricsQuality.choose(candidates, LyricsRepository.WORD_SYNCED)?.source)
        // Line sync asked for (word sync preference off): the order decides, as it did.
        assertEquals(LyricsSource.LRCLIB, LyricsQuality.choose(candidates, LyricsRepository.LINE_SYNCED)?.source)
        // A music video a few seconds longer than the recording keeps the words lit.
        assertTrue(LyricsQuality.wordsTrusted(words as Lyrics.Synced, listOf(lines), 184_000))
    }

    @Test fun lyricsKeptBeforeASourceWasAddedStillCount() {
        val now = listOf("LRC_RED", "LRCLIB", "BETTERLYRICS", "BETTERLYRICS_PORTATO", "KUGOU")
        // Kept with the same sources but the new one: shown at once, and a second off is the same cut.
        assertTrue(LyricsRepository.keptUnderEarlierSources("180|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU|true|null", 180, now, true, null))
        assertTrue(LyricsRepository.keptUnderEarlierSources("181|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU|true|null", 180, now, true, null))
        // Not when the listener changed them: a source turned off, an order changed, the preference, a pick.
        assertFalse(LyricsRepository.keptUnderEarlierSources("180|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU,NETEASE|true|null", 180, now, true, null))
        assertFalse(LyricsRepository.keptUnderEarlierSources("180|LRCLIB,LRC_RED,BETTERLYRICS,KUGOU|true|null", 180, now, true, null))
        assertFalse(LyricsRepository.keptUnderEarlierSources("180|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU|false|null", 180, now, true, null))
        assertFalse(LyricsRepository.keptUnderEarlierSources("180|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU|true|KUGOU", 180, now, true, null))
        // Another length is another recording; the very same settings are an ordinary hit, not this.
        assertFalse(LyricsRepository.keptUnderEarlierSources("190|LRC_RED,LRCLIB,BETTERLYRICS,KUGOU|true|null", 180, now, true, null))
        assertFalse(LyricsRepository.keptUnderEarlierSources("180|${now.joinToString(",")}|true|null", 180, now, true, null))
    }
}
