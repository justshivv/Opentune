package com.opentune.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaxsenixTest {
    @Test fun readsTheTtmlOutOfTheAnswer() {
        val ttml = Paxsenix.ttmlOf("""{"type":"TTML","content":"<tt xml:lang=\"en\"><body><div><p begin=\"1.0\" end=\"2.0\">Hi</p></div></body></tt>"}""")
        assertEquals("Hi", Ttml.parse(ttml!!).single().text)
        assertNull(Paxsenix.ttmlOf("""{"message":"Lyrics service busy; retry later","error":true}"""))
    }

    @Test fun onlyTheSameRecordingCounts() {
        val body = """{"results":{"songs":{"data":[
            {"id":"1","attributes":{"name":"Hello (Live)","artistName":"Adele","durationInMillis":300000,"isrc":"GBBKS1500214"}},
            {"id":"2","attributes":{"name":"Hello","artistName":"Adele","durationInMillis":295500,"isrc":"GBBKS1500215"}}
        ]}}}"""
        val songs = Paxsenix.appleSongs(body)
        val hit = LrcRed.best(songs.map { it.second }, "Hello", "Adele", 295_000)
        assertEquals("2", songs.first { it.second === hit }.first)
    }
}
