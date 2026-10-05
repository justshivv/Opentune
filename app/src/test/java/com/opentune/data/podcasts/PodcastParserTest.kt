package com.opentune.data.podcasts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against real YouTube Music answers, saved with tracking fields taken out. */
class PodcastParserTest {
    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject

    @Test fun readsAShowPage() {
        val page = PodcastParser.parseShow("MPSPPLq9fVK72pJBIzzgr5KQkVJOo4FZrDbCxA", fixture("podcast-show.json"))
        assertEquals("Nightcap", page.show.title)
        assertEquals("Nightcap", page.show.author)
        assertTrue(page.show.thumbnailUrl!!.contains("studio_square_thumbnail"))
        assertTrue(page.show.description!!.startsWith("Enjoy full episodes"))
        assertEquals(20, page.episodes.size)
        val first = page.episodes.first()
        assertEquals("VKLa_2Vbxho", first.videoId)
        assertTrue(first.title.startsWith("Unc, Ocho & Iso react to Lions-Panthers"))
        assertEquals("3 hr 8 min", first.durationText)
        assertEquals("11h ago", first.published)
        // Rows on a show page don't name the show; the page does.
        assertEquals("Nightcap", first.showTitle)
        assertEquals("MPSPPLq9fVK72pJBIzzgr5KQkVJOo4FZrDbCxA", first.showBrowseId)
        assertNotNull(page.continuation)
    }

    @Test fun readsPopularEpisodesAndSuggestedShows() {
        val root = fixture("podcast-shelves.json")
        val episodes = PodcastParser.episodesIn(root)
        assertEquals(24, episodes.size)
        assertEquals("Nightcap", episodes.first().showTitle)
        assertTrue(episodes.first().showBrowseId!!.startsWith("MPSP"))
        val shows = PodcastParser.showsIn(root)
        assertTrue(shows.size >= 10)
        assertEquals("La Corneta", shows.first().title)
        assertEquals("Los 40 México", shows.first().author)
    }

    @Test fun readsShowSearch() {
        val shows = PodcastParser.parseShowSearch(fixture("podcast-search-shows.json"))
        assertEquals("The Joe Rogan Experience", shows.first().title)
        assertEquals("PowerfulJRE", shows.first().author)
        assertTrue(shows.all { it.browseId.startsWith("MPSP") })
    }

    @Test fun readsEpisodeSearch() {
        val episodes = PodcastParser.parseEpisodeSearch(fixture("podcast-search-episodes.json"))
        val first = episodes.first()
        assertEquals("Mk5GC269WT0", first.videoId)
        assertEquals("Andrew Huberman: Regulate Stress in Real Time", first.title)
        assertEquals("Mayim Bialik's Breakdown", first.showTitle)
        assertEquals("Aug 23, 2022", first.published)
        assertEquals("MPSPPLedDhastjmeXkgwl4KJ5IIwnMZyUEfY-h", first.showBrowseId)
    }

    @Test fun anEpisodePlaysAsASongCreditedToTheShow() {
        val song = PodcastEpisode("Mk5GC269WT0", "Episode", "Show", thumbnailUrl = "https://x").toSong()
        assertEquals("Show", song.artist)
        assertEquals("Mk5GC269WT0", song.videoId)
    }
}
