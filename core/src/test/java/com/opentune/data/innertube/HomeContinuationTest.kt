package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeContinuationTest {
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private val shelf = """
        {"musicCarouselShelfRenderer": {
          "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Mixed for you"}]}}},
          "contents": [{"musicTwoRowItemRenderer": {
            "title": {"runs": [{"text": "My Mix 1"}]},
            "navigationEndpoint": {"browseEndpoint": {"browseId": "VLRDTMAK5uy_mix1"}}
          }}]
        }}
    """

    @Test
    fun firstPageTokenHangsOffTheSectionList() {
        val page = parse(
            """
            {"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
              "contents": [],
              "continuations": [{"nextContinuationData": {"continuation": "page2"}}]
            }}}}]}}}
            """,
        )
        assertEquals("page2", InnertubeParser.sectionListContinuation(page))
    }

    @Test
    fun laterPagesCarryShelvesAndTheNextToken() {
        val page = parse(
            """
            {"continuationContents": {"sectionListContinuation": {
              "contents": [$shelf],
              "continuations": [{"nextContinuationData": {"continuation": "page3"}}]
            }}}
            """,
        )
        assertEquals("page3", InnertubeParser.sectionListContinuation(page))
        assertEquals(listOf("Mixed for you"), InnertubeParser.parseHomeContinuation(page).map { it.title })
    }

    @Test
    fun tokenInAContinuationItemIsRead() {
        val page = parse(
            """
            {"continuationContents": {"sectionListContinuation": {"contents": [
              $shelf,
              {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "page4"}}}}
            ]}}}
            """,
        )
        assertEquals("page4", InnertubeParser.sectionListContinuation(page))
    }

    @Test
    fun lastPageHasNoToken() {
        val page = parse("""{"continuationContents": {"sectionListContinuation": {"contents": [$shelf]}}}""")
        assertNull(InnertubeParser.sectionListContinuation(page))
    }

    private fun carousel(title: String, avatar: Boolean, rows: Int) = """
        {"musicCarouselShelfRenderer": {
          "numItemsPerColumn": "$rows",
          "header": {"musicCarouselShelfBasicHeaderRenderer": {
            "title": {"runs": [{"text": "$title"}]},
            "strapline": {"runs": [{"text": "SHIVAM"}]}
            ${if (avatar) ""","thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://yt3.ggpht.com/a"}]}}}""" else ""}
          }},
          "contents": [{"musicTwoRowItemRenderer": {
            "title": {"runs": [{"text": "My Mix 1"}]},
            "navigationEndpoint": {"browseEndpoint": {"browseId": "VLRDTMAK5uy_mix1"}}
          }}]
        }}
    """

    @Test
    fun speedDialIsKnownByTheAccountPhotoInAnyLanguage() {
        val page = parse(
            """
            {"continuationContents": {"sectionListContinuation": {"contents": [
              ${carousel("स्पीड डायल", avatar = true, rows = 3)},
              ${carousel("Similar to Arijit Singh", avatar = true, rows = 1)},
              ${carousel("Mixed for you", avatar = false, rows = 1)}
            ]}}}
            """,
        )
        val shelves = InnertubeParser.parseHomeContinuation(page)
        assertEquals(listOf(true, false, false), shelves.map { it.personal })
    }
}
