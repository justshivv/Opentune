package com.opentune.data.lyrics

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseTest {
    private fun resource(name: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText())

    @Test fun picksTheSongOfTheRightLengthAndLeavesOutEdits() {
        val search = resource("netease-search-those-eyes.json")
        assertEquals(2092470574L, NetEase.pick(search, "Those Eyes", 220_000))
        // Nothing near a length no version has.
        assertNull(NetEase.pick(search, "Those Eyes", 400_000))
        // The sped-up edit only when that's what's playing.
        assertEquals(1964192320L, NetEase.pick(search, "Those Eyes (Sped Up)", 167_000))
    }

    @Test fun readsTheLyricsAndDropsTheCredits() {
        val lrc = NetEase.lrcOf(resource("netease-lyric-those-eyes.json"))
        assertNotNull(lrc)
        val lines = KuGou.clean(LrcParser.parse(lrc!!, 220_000), "Those Eyes", "New West")
        assertEquals("When we're out in the crowd", lines.first().text)
        assertTrue(lines.none { it.text.contains("作词") || it.text.contains("作曲") })
    }

    @Test fun anAnswerWithoutLyricsHasNone() {
        assertNull(NetEase.lrcOf(Json.parseToJsonElement("""{"lrc":{"lyric":"[00:00.00] 纯音乐，请欣赏"},"code":200}""")))
        assertNull(NetEase.lrcOf(Json.parseToJsonElement("""{"nolyric":true,"code":200}""")))
    }
}
