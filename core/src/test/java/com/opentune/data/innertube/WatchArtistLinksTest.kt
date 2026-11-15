package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchArtistLinksTest {
    private fun run(text: String, id: String? = null, type: String = "MUSIC_PAGE_TYPE_ARTIST") =
        if (id == null) """{"text":"$text"}"""
        else """{"text":"$text","navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}}"""

    @Test fun everyArtistTheSongLinksToByName() {
        val runs = listOf(run("Pritam", "UCpritam"), run(" & "), run("KK", "UCkk"), run(" • "), run("Gangster", "MPREgangster", "MUSIC_PAGE_TYPE_ALBUM")).joinToString(",")
        val json = """{"contents":{"playlistPanelVideoRenderer":{"videoId":"v1","title":{"runs":[{"text":"Tu Hi Meri Shab Hai"}]},"longBylineText":{"runs":[$runs]}}}}"""
        val links = InnertubeParser.watchArtistLinks(Json.parseToJsonElement(json), "v1")
        assertEquals(mapOf("pritam" to "UCpritam", "kk" to "UCkk"), links)
        // Another song's row says nothing about this one.
        assertEquals(emptyMap<String, String>(), InnertubeParser.watchArtistLinks(Json.parseToJsonElement(json), "other"))
    }
}
