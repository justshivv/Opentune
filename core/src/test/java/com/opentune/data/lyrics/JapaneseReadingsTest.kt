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
}
