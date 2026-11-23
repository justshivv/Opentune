package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicVideoTypeTest {
    private fun row(id: String, type: String) = """
        {"playlistPanelVideoRenderer": {"videoId": "$id", "title": {"runs": [{"text": "A song"}]},
          "navigationEndpoint": {"watchEndpoint": {"videoId": "$id",
            "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "$type"}}}}}}
    """

    // An art track on its own, and a song whose music video is the counterpart of its track.
    private val queue = Json.parseToJsonElement("""
        {"contents": [
          ${row("art1", "MUSIC_VIDEO_TYPE_ATV")},
          {"playlistPanelVideoWrapperRenderer": {
            "primaryRenderer": ${row("track2", "MUSIC_VIDEO_TYPE_ATV")},
            "counterpart": [{"counterpartRenderer": ${row("video2", "MUSIC_VIDEO_TYPE_OMV")}}]
          }}
        ]}
    """)

    @Test fun anArtTrackIsNamedSo() {
        assertEquals("MUSIC_VIDEO_TYPE_ATV", InnertubeParser.musicVideoType(queue, "art1"))
    }

    @Test fun aCounterpartIsReadToo() {
        assertEquals("MUSIC_VIDEO_TYPE_OMV", InnertubeParser.musicVideoType(queue, "video2"))
        assertEquals("MUSIC_VIDEO_TYPE_ATV", InnertubeParser.musicVideoType(queue, "track2"))
    }

    @Test fun aSongTheQueueDoesNotListHasNoType() {
        assertNull(InnertubeParser.musicVideoType(queue, "other"))
    }
}
