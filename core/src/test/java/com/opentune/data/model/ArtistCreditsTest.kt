package com.opentune.data.model

import com.opentune.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
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
