package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseReadingsTest {
    @Test fun readsTheRomajiOutOfAResponse() {
        val body = "[[[null,null,null,\"Deaeru sekai-sen erabetara yokatta ◆ motto chigau seikaku de\"]],null,\"ja\",null,null,null,1,[],[[\"ja\"],null,[1],[\"ja\"]]]"
        val read = JapaneseReadings.parse(body)!!.split("◆").map { it.trim() }
        assertEquals(listOf("Deaeru sekai-sen erabetara yokatta", "motto chigau seikaku de"), read)
    }

    @Test fun aBrokenResponseReadsAsNothing() {
        assertNull(JapaneseReadings.parse("not json"))
        assertNull(JapaneseReadings.parse("[[]]"))
    }

    @Test fun onlyJapaneseWithKanjiIsSentOff() {
        assertTrue(JapaneseReadings.wanted(listOf("出会える世界線 選べたらよかった")))
        assertFalse(JapaneseReadings.wanted(listOf("もっとちがう")))
        assertFalse(JapaneseReadings.wanted(listOf("我爱你")))
        assertFalse(JapaneseReadings.wanted(listOf("plain words")))
    }

    @Test fun syllableSpacesAreJoinedBeforeReading() {
        // As a syllable-timed source sends them; read apart, 別 was "betsu" and 的 "mato".
        org.junit.Assert.assertEquals("繰り返す出会いと別れに", JapaneseReadings.joined("繰 り 返す 出会い と 別 れ に"))
        org.junit.Assert.assertEquals("個人的な嵐は誰かの", JapaneseReadings.joined(" 個人 的 な 嵐 は 誰 か の "))
        // Spaces beside Latin words stay.
        org.junit.Assert.assertEquals("Baby 君が好き yeah", JapaneseReadings.joined("Baby 君 が 好き yeah"))
    }
}
