package com.opentune.data.metadata

import com.opentune.data.lastfm.LastFm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every song, artist and text here is invented. */
class SongWikiTest {
    private fun page(key: String, title: String, description: String) =
        """{"id":1,"key":"$key","title":"$title","excerpt":"","description":"$description"}"""

    @Test fun takesOnlyTheSongsOwnArticle() {
        val body = """{"pages":[
            ${page("Paper_Lanterns_(novel)", "Paper Lanterns (novel)", "2009 novel by Ira Bell")},
            ${page("Paper_Lanterns_(Nova_Reyes_song)", "Paper Lanterns (Nova Reyes song)", "2025 single by Nova Reyes and Tomas Ilvane")},
            ${page("Paper_Lanterns", "Paper Lanterns", "Song by the Kestrels")}
        ]}"""
        assertEquals("Paper_Lanterns_(Nova_Reyes_song)", SongWiki.pickPage(body, "Paper Lanterns (Official Video)", "Nova Reyes, Tomas Ilvane"))
        // A namesake's song, or a page about something else, is never taken.
        assertNull(SongWiki.pickPage(body, "Paper Lanterns", "Elin Marsh"))
        assertNull(SongWiki.pickPage(body, "Harbour Lights", "Nova Reyes"))
    }

    @Test fun readsTheLead() {
        val summary = """{"type":"standard","title":"Paper Lanterns","extract":"\"Paper Lanterns\" is a song by Nova Reyes, released in 2025 as the lead single.",
            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Paper_Lanterns"},"mobile":{"page":"https://en.m.wikipedia.org/wiki/Paper_Lanterns"}}}"""
        val article = SongWiki.parseSummary(summary)!!
        assertEquals("https://en.m.wikipedia.org/wiki/Paper_Lanterns", article.url)
        assertNull(SongWiki.parseSummary("""{"type":"disambiguation","extract":"Paper Lanterns may refer to many things, listed below for you.","content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/P"}}}"""))
        assertNull(SongWiki.parseSummary("""{"type":"standard","extract":"Too short.","content_urls":{"mobile":{"page":"https://en.m.wikipedia.org/wiki/P"}}}"""))
    }

    @Test fun bareTitleDropsYouTubesAdditions() {
        assertEquals("Paper Lanterns", SongWiki.bareTitle("Paper Lanterns (From \"Harbour\") [Lyric Video]"))
        assertEquals("Paper Lanterns", SongWiki.bareTitle("Paper Lanterns - Acoustic"))
    }

    @Test fun readsLastFmWikiWithoutItsReadMoreLink() {
        val body = """{"track":{"name":"Paper Lanterns","url":"https://www.last.fm/music/Nova+Reyes/_/Paper+Lanterns",
            "wiki":{"summary":"A slow, warm duet about a harbour festival &amp; the night it ends. <a href=\"https://www.last.fm/music/Nova+Reyes/_/Paper+Lanterns/+wiki\">Read more on Last.fm</a>."}}}"""
        val wiki = LastFm.parseWiki(body)!!
        assertEquals("A slow, warm duet about a harbour festival & the night it ends.", wiki.text)
        assertEquals("https://www.last.fm/music/Nova+Reyes/_/Paper+Lanterns", wiki.url)
        assertNull(LastFm.parseWiki("""{"track":{"name":"Paper Lanterns","url":"https://www.last.fm/music/x"}}"""))
        assertNull(LastFm.parseWiki("""{"error":6,"message":"Track not found"}"""))
    }
}
