package com.opentune.data.model

import com.opentune.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ArtistCreditsTest {
    @Test fun namedCreditsBeatCompilationLabelsWithoutGuessingMissingNames() {
        for (label in listOf("Various Artist", "VARIOUS ARTISTS", "V.A.", "Various Artists - Topic", "Unknown artist")) {
            assertTrue(isPlaceholderArtist(label))
            assertEquals("Mika Singh & Yo Yo Honey Singh", preferredArtist(label, "Mika Singh & Yo Yo Honey Singh"))
        }
        assertFalse(isPlaceholderArtist("The Unknown"))
        assertEquals("Various Artist", preferredArtist("Various Artist", null))
    }

    @Test fun fullerArtistKeepsThePrintedBylineWhenItExtendsTheLinkedName() {
        // The second singer is printed on the row but linked nowhere.
        assertEquals("Mika Singh & Yo Yo Honey Singh", fullerArtist("Mika Singh", "Mika Singh & Yo Yo Honey Singh"))
        assertEquals("Calvin Harris, Dua Lipa", fullerArtist("Calvin Harris", "Calvin Harris, Dua Lipa"))
        // Nothing fuller to say: the linked name stands, exactly as before.
        assertEquals("Karan Aujla", fullerArtist("Karan Aujla", "Karan Aujla"))
        assertEquals("Karan Aujla", fullerArtist("Karan Aujla", null))
        assertEquals("Karan Aujla", fullerArtist(null, "Karan Aujla"))
        // A band billed as one run splits into names that are not it: not trusted.
        assertEquals("Simon & Garfunkel", fullerArtist("Simon & Garfunkel", "Simon & Garfunkel & Paul Simon"))
        // A segment naming somebody else is not this row's credit.
        assertEquals("Arijit Singh", fullerArtist("Arijit Singh", "Pritam, Shreya Ghoshal"))
        // A placeholder never anchors; the printed credit stands on its own.
        assertEquals("Mika Singh & Yo Yo Honey Singh", fullerArtist("Various Artists", "Mika Singh & Yo Yo Honey Singh"))
        assertNull(fullerArtist(null, null))
    }

    @Test fun searchRowNamesEveryLinkedArtist() {
        // The byline as one segment, both artists linked.
        assertEquals("Calvin Harris, Dua Lipa", searchArtist(calvinRuns(" & ")))
        // The same credit split across bullet segments: the printed text the
        // parser can see names only the first artist, but the links name both.
        assertEquals("Calvin Harris, Dua Lipa", searchArtist(calvinRuns(" \u2022 ")))
        // One linked artist: the credit stands exactly as before.
        assertEquals("Calvin Harris", searchArtist(listOf(artistRun("Calvin Harris", "UC1"))))
    }

    private fun artistRun(name: String, id: String): String =
        """{"text": "$name", "navigationEndpoint": {"browseEndpoint": {"browseId": "$id",
           "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig":
           {"pageType": "MUSIC_PAGE_TYPE_ARTIST"}}}}}"""

    private fun calvinRuns(joiner: String): List<String> = listOf(
        """{"text": "Song"}""", """{"text": " \u2022 "}""",
        artistRun("Calvin Harris", "UC1"), """{"text": "$joiner"}""", artistRun("Dua Lipa", "UC2"),
    )

    private fun searchArtist(subtitleRuns: List<String>): String {
        val root = Json.parseToJsonElement("""{
          "musicResponsiveListItemRenderer": {
            "playlistItemData": {"videoId": "one-kiss"},
            "flexColumns": [
              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "One Kiss"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [${subtitleRuns.joinToString(",")}]}}}
            ],
            "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails":
              [{"url": "https://i.ytimg.com/vi/x/default.jpg", "width": 60, "height": 60}]}}}
          }
        }""")
        return InnertubeParser.parseSearchSongs(root.jsonObject).single().artist
    }

    @Test fun watchQueueUsesSpecificShortCreditWhenLongCreditIsACompilation() {
        val root = Json.parseToJsonElement("""{
          "playlistPanelVideoRenderer": {
            "videoId": "same-video", "title": {"runs": [{"text": "Mast Kalander"}]},
            "longBylineText": {"runs": [{"text": "Various Artist • Compilation • 2014"}]},
            "shortBylineText": {"runs": [{"text": "Mika Singh & Yo Yo Honey Singh"}]}
          }
        }""")
        val song = InnertubeParser.parseWatchQueue(root).single()
        assertEquals("same-video", song.videoId)
        assertEquals("Mika Singh & Yo Yo Honey Singh", song.artist)
    }

    @Test fun watchQueuePreservesNamesWhenBulletSharesTheSameTextRun() {
        val root = Json.parseToJsonElement("""{
          "playlistPanelVideoRenderer": {
            "videoId": "one-run", "title": {"runs": [{"text": "Song"}]},
            "longBylineText": {"runs": [{"text": "Artist One & Artist Two • Album • 2025"}]}
          }
        }""")
        assertEquals("Artist One & Artist Two", InnertubeParser.parseWatchQueue(root).single().artist)
    }
}
