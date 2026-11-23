package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GridShelfTest {
    @Test
    fun aGenreThatComesAsOneGridIsNotEmpty() {
        // A small genre (Haryanvi) whose playlists come as one grid, no carousels.
        val page = Json.parseToJsonElement(
            """
            {"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
              {"gridRenderer": {
                "header": {"gridHeaderRenderer": {"title": {"runs": [{"text": "Haryanvi hits"}]}}},
                "items": [
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Haryanvi Superhits"}]},
                    "subtitle": {"runs": [{"text": "YouTube Music"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLRDCLAK5uy_haryanvi1"}}
                  }},
                  {"musicTwoRowItemRenderer": {
                    "title": {"runs": [{"text": "Desi Beats"}]},
                    "subtitle": {"runs": [{"text": "YouTube Music"}]},
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "VLRDCLAK5uy_haryanvi2"}}
                  }}
                ]
              }}
            ]}}}}]}}}
            """,
        ).jsonObject
        val shelves = InnertubeParser.parseHome(page)
        assertEquals(1, shelves.size)
        assertEquals("Haryanvi hits", shelves[0].title)
        assertEquals(listOf("VLRDCLAK5uy_haryanvi1", "VLRDCLAK5uy_haryanvi2"), shelves[0].items.map { it.browseId })
    }
}
