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
}
