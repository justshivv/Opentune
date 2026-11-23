package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastChannelTest {
    private fun page() = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/innertube/podcast-channel.json")!!.bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun aPodcastersChannelKeepsItsShows() {
        val artist = InnertubeParser.parseArtistPage(page())
        val shows = artist.sections.flatMap { it.items }.mapNotNull { it.browseId }.filter { it.startsWith("MPSP") }
        assertTrue("the channel's podcast shows open as podcasts", shows.isNotEmpty())
    }
}
