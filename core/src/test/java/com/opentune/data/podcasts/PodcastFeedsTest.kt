package com.opentune.data.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PodcastFeedsTest {
    private val feedUrl = "https://feeds.example.org/tiny-show.rss"

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/">
        <channel>
          <title>Tiny Show</title>
          <itunes:author>Two Friends</itunes:author>
          <description><![CDATA[<p>A small show about <b>small</b> things &amp; more.</p>]]></description>
          <image><url>https://img.example.org/small.png</url><title>Tiny Show</title></image>
          <itunes:image href="https://img.example.org/show.jpg"/>
          <item>
            <title>Episode 1: Hello</title>
            <pubDate>Mon, 02 Mar 2026 09:00:00 +0000</pubDate>
            <enclosure url="https://cdn.example.org/ep1.mp3" length="1000" type="audio/mpeg"/>
            <itunes:duration>45:10</itunes:duration>
            <description>First one.</description>
          </item>
          <item>
            <title>Episode 2: Again</title>
            <pubDate>Tue, 14 Apr 2026 09:00:00 +0000</pubDate>
            <enclosure url="http://cdn.example.org/ep2.m4a" length="1000" type="audio/x-m4a"/>
            <itunes:duration>4012</itunes:duration>
            <itunes:image href="https://img.example.org/ep2.jpg"/>
          </item>
          <item>
            <title>A post with no audio</title>
            <pubDate>Wed, 15 Apr 2026 09:00:00 +0000</pubDate>
          </item>
        </channel>
        </rss>"""

    @Test fun aFeedReadsAsAShowWithItsEpisodesNewestFirst() {
        val page = PodcastFeeds.parse(feed, feedUrl)!!
        assertEquals("Tiny Show", page.show.title)
        assertEquals("Two Friends", page.show.author)
        assertEquals("https://img.example.org/show.jpg", page.show.thumbnailUrl)
        assertEquals("A small show about small things & more.", page.show.description)
        assertEquals(PodcastFeeds.showId(feedUrl), page.show.browseId)
        // The post without audio is left out; the newest comes first.
        assertEquals(listOf("Episode 2: Again", "Episode 1: Hello"), page.episodes.map { it.title })
        val newest = page.episodes.first()
        assertEquals("http://cdn.example.org/ep2.m4a", PodcastFeeds.urlOf(newest.videoId))
        assertEquals("https://img.example.org/ep2.jpg", newest.thumbnailUrl)
        assertEquals("1 hr 7 min", newest.durationText)
        assertEquals("45 min", page.episodes[1].durationText)
        assertEquals("https://img.example.org/show.jpg", page.episodes[1].thumbnailUrl)
        assertEquals(page.show.browseId, newest.showBrowseId)
        assertEquals(null, page.continuation)
    }

    @Test fun idsCarryTheirAddresses() {
        val show = PodcastFeeds.showId(feedUrl)
        assertTrue(PodcastFeeds.isFeedShow(show))
        assertEquals(feedUrl, PodcastFeeds.feedUrlOf(show))
        val ep = PodcastFeeds.episodeId("https://cdn.example.org/a b/ep.mp3?x=1&y=2")
        assertTrue(PodcastFeeds.isFeedEpisode(ep))
        assertEquals("https://cdn.example.org/a b/ep.mp3?x=1&y=2", PodcastFeeds.urlOf(ep))
        // Not a YouTube track: kept off YouTube's stream lookups, lyrics and radio.
        assertTrue(!com.opentune.data.isYouTubeId(ep))
    }

    @Test fun notAFeed() {
        assertNull(PodcastFeeds.parse("<html><body>Hello</body></html>", feedUrl))
        assertNull(PodcastFeeds.parse("not xml at all", feedUrl))
    }

    @Test fun theDirectoryGivesShowsWithFeeds() {
        val body = """{"resultCount":3,"results":[
            {"collectionName":"Tiny Show","artistName":"Two Friends","feedUrl":"https://feeds.example.org/tiny-show.rss","artworkUrl600":"https://img.example.org/600.jpg"},
            {"collectionName":"No Feed Show","artistName":"Someone"},
            {"collectionName":"","feedUrl":"https://feeds.example.org/x.rss"}]}"""
        val shows = PodcastFeeds.parseDirectory(body)
        assertEquals(listOf("Tiny Show"), shows.map { it.title })
        assertEquals(PodcastFeeds.showId("https://feeds.example.org/tiny-show.rss"), shows[0].browseId)
        assertEquals("https://img.example.org/600.jpg", shows[0].thumbnailUrl)
    }

    @Test fun searchKeepsYouTubeShowsFirstAndAddsTheOnesItLacks() {
        val yt = listOf(PodcastShow("MPSPPLa", "Big Show"), PodcastShow("MPSPPLb", "Tiny Show"))
        val dir = listOf(PodcastShow("rss:x", "Tiny  Show!"), PodcastShow("rss:y", "Niche Show"))
        assertEquals(listOf("MPSPPLa", "MPSPPLb", "rss:y"), Podcasts.mergeShows(Result.success(yt), Result.success(dir)).map { it.browseId })
        // One source down: the other's shows still come back.
        assertEquals(listOf("rss:x", "rss:y"), Podcasts.mergeShows(Result.failure(java.io.IOException()), Result.success(dir)).map { it.browseId })
    }

    @Test fun feedAddressesAreRecognised() {
        assertTrue(PodcastFeeds.looksLikeFeed("https://anchor.fm/s/13ff3c38/podcast/rss"))
        assertTrue(PodcastFeeds.looksLikeFeed("https://rss.buzzsprout.com/1877933.rss"))
        assertTrue(PodcastFeeds.looksLikeFeed("https://feeds.megaphone.fm/SHOW123"))
        assertTrue(!PodcastFeeds.looksLikeFeed("https://example.com/blog/post"))
    }
}
