package com.opentune.data.lyrics

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuGouTest {
    private val json = Json

    // The shape of a real search answer, cut down to the fields that matter.
    private val search = json.parseToJsonElement(
        """
        {"status":200,"candidates":[
          {"id":"111","accesskey":"AAA","singer":"The Weeknd","song":"Blinding Lights","duration":215000},
          {"id":"337230074","accesskey":"552F3B904D38F5DCBDC0801F01E2BEBB","singer":"The Weeknd","song":"Blinding Lights","duration":200097},
          {"id":"222","accesskey":"BBB","singer":"The Weeknd","song":"Blinding Lights","duration":201900}
        ]}
        """,
    )

    @Test fun picksTheCandidateClosestInLength() {
        assertEquals("337230074" to "552F3B904D38F5DCBDC0801F01E2BEBB", KuGou.pick(search, 200_000))
    }

    @Test fun refusesCandidatesOfAnotherLength() {
        assertNull(KuGou.pick(search, 260_000))
        assertNull(KuGou.pick(json.parseToJsonElement("""{"candidates":[]}"""), 200_000))
    }

    @Test fun decodesTheDownloadAndDropsTheCredits() {
        val lrc = """
            [ti:Blinding Lights]
            [ar:The Weeknd]
            [00:00.00]Blinding Lights - The Weeknd
            [00:04.69]Lyrics by：Max Martin/Oscar Holter
            [00:09.38]Composed by：Max Martin/Oscar Holter
            [00:14.07]Yeah
            [00:27.67]I've been tryna call
            [00:30.45]I've been on my own for long enough
        """.trimIndent()
        val download = json.parseToJsonElement(
            """{"status":200,"fmt":"lrc","content":"${Base64.getEncoder().encodeToString(lrc.toByteArray())}"}""",
        )
        val text = KuGou.lrcOf(download)!!
        val lines = KuGou.clean(LrcParser.parse(text, 200_000), "Blinding Lights", "The Weeknd")
        assertEquals(listOf("Yeah", "I've been tryna call", "I've been on my own for long enough"), lines.map { it.text })
        assertEquals(14_070L, lines.first().startMs)
    }

    @Test fun skipsARemixOfTheSameLength() {
        val withRemix = json.parseToJsonElement(
            """{"candidates":[
              {"id":"1","accesskey":"R","song":"Dynamite (EDM Remix)","duration":199000},
              {"id":"2","accesskey":"O","song":"Dynamite","duration":199300}
            ]}""",
        )
        assertEquals("2" to "O", KuGou.pick(withRemix, 199_000, "Dynamite"))
        assertEquals("1" to "R", KuGou.pick(withRemix, 199_000, "Dynamite (EDM Remix)"))
    }

    @Test fun dropsAHeaderNamingAnotherVersion() {
        val lrc = "[00:00.00]Dynamite (EDM Remix) - BTS (防弹少年团)\n[00:00.05]Remixed by：FRANTS\n[00:00.14]Mixing Assistant：Matt Wolach\n[00:05.00]Cause I, I, I'm in the stars tonight"
        val lines = KuGou.clean(LrcParser.parse(lrc, 199_000), "Dynamite", "BTS")
        assertEquals(listOf("Cause I, I, I'm in the stars tonight"), lines.map { it.text })
    }

    @Test fun dropsChineseCreditLinesToo() {
        val lrc = "[00:00.00]作词：林夕\n[00:01.00]作曲：陈小霞\n[00:10.00]第一句\n[00:15.00]第二句"
        val lines = KuGou.clean(LrcParser.parse(lrc, 60_000), "歌", "人")
        assertEquals(listOf("第一句", "第二句"), lines.map { it.text })
    }
}
