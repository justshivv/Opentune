package com.opentune.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    private val fixture = javaClass.classLoader!!.getResource("radio-browser-jazz.json")!!.readText()

    @Test fun parsesARealSearchAnswer() {
        val stations = Radio.parse(fixture)
        assertEquals(3, stations.size)
        val first = stations.first()
        assertEquals("d28420a4-eccf-47a2-ace1-088c7e7cb7e0", first.uuid)
        assertEquals("101 SMOOTH JAZZ", first.name)
        assertEquals("http://jking.cdnstream1.com/b22139_128mp3", first.streamUrl)
        assertEquals("MP3", first.codec)
        assertEquals(192, first.bitrate)
        assertFalse(first.hls)
        assertEquals(listOf("easy listening", "jazz", "smooth jazz"), first.tags)
        assertEquals("radio:d28420a4-eccf-47a2-ace1-088c7e7cb7e0", first.id)
        assertTrue(first.details.startsWith("MP3 · 192 kbps"))
    }

    @Test fun skipsStationsWithoutAStreamOrName() {
        val body = """[
            {"stationuuid":"a","name":"No stream","url":"","url_resolved":""},
            {"stationuuid":"b","name":"  ","url_resolved":"https://x/stream"},
            {"stationuuid":"c","name":"Good","url":"https://x/fallback","url_resolved":"","hls":1}
        ]"""
        val stations = Radio.parse(body)
        assertEquals(listOf("c"), stations.map { it.uuid })
        assertEquals("https://x/fallback", stations.single().streamUrl)
        assertTrue(stations.single().hls)
    }

    @Test fun stationIdsAreNotYouTubeIds() {
        assertTrue(Radio.isRadio("radio:abc"))
        assertFalse(com.opentune.data.isYouTubeId("radio:abc"))
        assertTrue(com.opentune.data.isYouTubeId("dQw4w9WgXcQ"))
    }

    @Test fun aStationPlaysAsALiveSong() {
        val song = Radio.parse(fixture).first().toSong()
        assertEquals("radio:d28420a4-eccf-47a2-ace1-088c7e7cb7e0", song.videoId)
        assertEquals("Live radio", song.artist)
    }
}
