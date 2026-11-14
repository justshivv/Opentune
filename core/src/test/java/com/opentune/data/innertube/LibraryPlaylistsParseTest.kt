package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library's playlists, read from the layouts YouTube Music has sent, and from the ones the strict readers miss. */
class LibraryPlaylistsParseTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun twoRow(title: String, id: String?, extra: String = "") = """
        {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title"}]},"subtitle":{"runs":[{"text":"Playlist • 12 songs"}]},
         "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$title.jpg","width":226,"height":226}]}}},
         "navigationEndpoint":${if (id != null) """{"browseEndpoint":{"browseId":"$id"}}""" else """{"createPlaylistEndpoint":{}}"""} $extra}}
    """

    @Test fun theClassicGridGivesItsPlaylistsAndSkipsTheNewPlaylistTile() {
        val page = json("""{"contents":{"gridRenderer":{"items":[${twoRow("New playlist", null)},${twoRow("Road trip", "VLPL111")},${twoRow("Gym", "VLPL222")}]}}}""")
        assertEquals(listOf("Road trip", "Gym"), InnertubeParser.parseLibraryItemPage(page).items.map { it.title })
    }

    @Test fun aListLayoutGivesItsPlaylistsToo() {
        val row = { t: String, id: String -> """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$t"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Playlist"}]}}}],"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}}""" }
        val page = json("""{"contents":{"musicShelfRenderer":{"contents":[${row("Chill", "VLPL1")},${row("Focus", "VLPL2")}]}}}""")
        assertEquals(listOf("Chill", "Focus"), InnertubeParser.parseLibraryItemPage(page).items.map { it.title })
    }

    private val lockups = """
        {"contents":{"gridRenderer":{"items":[
          {"lockupViewModel":{"contentId":"PLabc","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
            "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://img/a.jpg"},{"url":"https://img/a-big.jpg"}]}}}}},
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Late night"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Playlist"}}]}]}}}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"VLPLabc"}}}}}}},
          {"lockupViewModel":{"contentId":"PLdef",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Throwbacks"}}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"VLPLdef"}}}}}}},
          {"lockupViewModel":{"contentId":"MPREb_album","metadata":{"lockupMetadataViewModel":{"title":{"content":"An album"}}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"MPREb_album"}}}}}}}
        ]}}}
    """

    @Test fun theNewerCardsAreFoundByTheLooseReaderWhenTheStrictOnesFindNothing() {
        val page = json(lockups)
        assertTrue("the card readers know nothing of lockups", InnertubeParser.parseLibraryItemPage(page).items.isEmpty())
        val found = InnertubeParser.looseLibraryPlaylists(page)
        assertEquals(listOf("Late night", "Throwbacks"), found.map { it.title })
        assertEquals(listOf("VLPLabc", "VLPLdef"), found.map { it.browseId })
        assertEquals("https://img/a-big.jpg", found[0].thumbnailUrl)
        // The album is not a playlist.
        assertTrue(found.none { it.title == "An album" })
    }

    @Test fun theLooseReaderTakesClassicCardsToo_OnceEach() {
        val page = json("""{"a":[${twoRow("Road trip", "VLPL111")},${twoRow("Road trip", "VLPL111")},${twoRow("Artist", "UCxyz")}]}""")
        assertEquals(listOf("VLPL111"), InnertubeParser.looseLibraryPlaylists(page).map { it.browseId })
    }

    @Test fun aPlainPlaylistIdGetsItsPagePrefix() {
        val page = json("""{"lockupViewModel":{"contentId":"PLplain","metadata":{"lockupMetadataViewModel":{"title":{"content":"Plain"}}}}}""")
        assertEquals(listOf("VLPLplain"), InnertubeParser.looseLibraryPlaylists(page).map { it.browseId })
    }

    @Test fun signedOutIsReadFromYouTubesOwnFlag() {
        fun page(flag: String?): JsonElement {
            val logged = if (flag != null) ",{\"key\":\"logged_in\",\"value\":\"$flag\"}" else ""
            return json("{\"responseContext\":{\"serviceTrackingParams\":[{\"service\":\"GFEEDBACK\",\"params\":[{\"key\":\"is_viewed_live\",\"value\":\"false\"}$logged]}]}}")
        }
        assertTrue(InnertubeParser.answeredSignedOut(page("0")))
        assertFalse(InnertubeParser.answeredSignedOut(page("1")))
        // No flag: a layout change must not be taken for a sign-out.
        assertFalse(InnertubeParser.answeredSignedOut(page(null)))
        assertFalse(InnertubeParser.answeredSignedOut(json("{}")))
    }

    @Test fun theLogLineSaysWhatThePageHeld() {
        val line = InnertubeParser.describeLibrary(json(lockups))
        assertTrue(line, line.contains("lockupViewModel=3"))
        assertTrue(line, line.contains("musicTwoRowItemRenderer=0"))
        assertTrue(line, line.contains("signedOut=false"))
    }
}
