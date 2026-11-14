package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchQueueVersionsTest {
    private fun row(id: String, title: String, byline: String) = """
        {"playlistPanelVideoRenderer": {"videoId": "$id", "title": {"runs": [{"text": "$title"}]},
          "longBylineText": {"runs": [{"text": "$byline"}]}, "lengthText": {"runs": [{"text": "3:40"}]}}}
    """

    // The video plays (primary); its catalogue track is the counterpart, as YouTube Music sends it.
    private val queue = Json.parseToJsonElement("""
        {"contents": [
          {"playlistPanelVideoWrapperRenderer": {
            "primaryRenderer": ${row("video1", "Song One (Official Video)", "Artist • 12M views • 300K likes")},
            "counterpart": [{"counterpartRenderer": ${row("track1", "Song One", "Artist • Album • 2024")}}]
          }},
          ${row("track2", "Song Two", "Artist • Album • 2024")}
        ]}
    """)

    @Test fun oneVersionOfEachSong() {
        assertEquals(listOf("video1", "track2"), InnertubeParser.parseWatchQueue(queue).map { it.videoId })
    }

    @Test fun songsOnlyTakesTheTrackOverItsVideo() {
        assertEquals(listOf("track1", "track2"), InnertubeParser.parseWatchQueue(queue, songsOnly = true).map { it.videoId })
    }

    @Test fun lookingUpByIdSeesBothVersions() {
        assertEquals(setOf("video1", "track1", "track2"), InnertubeParser.parseWatchQueue(queue, allVersions = true).map { it.videoId }.toSet())
    }
}
