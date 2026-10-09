package com.opentune.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsSourcesTest {
    @Test fun readsCaptionXmlAndDropsCues() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><timedtext format="3"><body>
            <p t="1360" d="1680">[&#9834;&#9834;&#9834;]</p>
            <p t="18640" d="3240">♪ We&#39;re out on the road ♪</p>
            <p t="22000" d="2000">and the <s t="0">night</s> is long</p>
            <p t="25000" d="1500">[Music]</p>
            <p t="27000" d="2500">♪ singing along ♪</p>
            </body></timedtext>"""
        val lines = YouTubeCaptions.parse(xml)
        assertEquals(listOf("We're out on the road", "and the night is long", "singing along"), lines.map { it.text })
        assertEquals(18_640L, lines[0].startMs)
        assertEquals(21_880L, lines[0].endMs)
        assertTrue(lines.none { it.wordSynced })
    }

    @Test fun readsCaptionJsonAndCalmsCapitals() {
        val json = """{"events":[{"tStartMs":1000,"dDurationMs":2000,"segs":[{"utf8":"HELLO "},{"utf8":"THERE"}]},{"tStartMs":4000,"dDurationMs":1000,"segs":[{"utf8":"GOOD\nNIGHT"}]}]}"""
        assertEquals(listOf("Hello there", "Good night"), YouTubeCaptions.parse(json).map { it.text })
    }

    @Test fun picksAHandMadeTrackInTheSongsLanguage() {
        fun t(lang: String, kind: String? = null) = Json.parseToJsonElement("""{"languageCode":"$lang","baseUrl":"u-$lang"${if (kind != null) ""","kind":"$kind"""" else ""}}""").jsonObject
        assertEquals("es-419", YouTubeCaptions.pick(listOf(t("en"), t("es", "asr"), t("es-419")))?.get("languageCode")?.toString()?.trim('"'))
        assertNull(YouTubeCaptions.pick(listOf(t("en", "asr"))))
        assertEquals("ja", YouTubeCaptions.pick(listOf(t("ja")))?.get("languageCode")?.toString()?.trim('"'))
        // Several hand-made tracks and no automatic one to say which is the song's: none.
        assertNull(YouTubeCaptions.pick(listOf(t("en"), t("de"))))
    }

    @Test fun unisonSkipsUntrustedEntries() {
        fun answer(extra: String, lyrics: String = "[00:01.00] One\\n[00:05.00] Two", format: String = "lrc") =
            """{"success":true,"data":{"lyrics":"$lyrics","format":"$format","confidence":"low","effectiveScore":0,"voteCount":0$extra}}"""
        assertNull(Unison.parse(answer(""), 200_000))
        val voted = Unison.parse(answer(""","voteCount":3,"effectiveScore":2.5"""), 200_000) as Lyrics.Synced
        assertEquals(listOf("One", "Two"), voted.lines.map { it.text })
        assertEquals("Unison", voted.source)
        assertNull(Unison.parse(answer(""","voteCount":3,"effectiveScore":2.5,"hidden":true"""), 200_000))
    }

    @Test fun readsWordTimedTtml() {
        val ttml = """<tt><body><div>
            <p begin="00:01.000" end="00:03.500"><span begin="00:01.000" end="00:01.600">Hold</span> <span begin="00:01.600" end="00:02.400">on</span> <span begin="00:02.400" end="00:03.500">tight</span></p>
            <p begin="4.0s" end="6.0s">plain line</p>
            </div></body></tt>"""
        val lines = Ttml.parse(ttml)
        assertEquals("Hold on tight", lines[0].text)
        assertTrue(lines[0].wordSynced)
        assertEquals(1_600L, lines[0].words[1].startMs)
        assertEquals(4_000L, lines[1].startMs)
        assertEquals(false, lines[1].wordSynced)
        assertEquals(62_345L, Ttml.time("1:02.345"))
        assertEquals(3_662_000L, Ttml.time("01:01:02"))
    }
}
