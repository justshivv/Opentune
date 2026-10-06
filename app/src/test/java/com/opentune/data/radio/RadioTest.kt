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

    private val nearby = javaClass.classLoader!!.getResource("radio-browser-nearby.json")!!.readText()

    @Test fun nearbyStationsComeNearestFirstWithTheirDistance() {
        val found = Radio.parseNearby(nearby, 28.61, 77.21)
        assertEquals(8, found.size)
        assertEquals("AIR Delhi FM Gold", found.first().station.name)
        assertEquals(0.19, found.first().distanceKm, 0.01)
        assertTrue(found.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
        assertEquals("Delhi", found.first().station.state)
    }

    @Test fun distanceFallsBackToTheStationsCoordinates() {
        val body = """[{"stationuuid":"x","name":"Somewhere FM","url_resolved":"https://x/s","geo_lat":28.7,"geo_long":77.1,"geo_distance":null}]"""
        val km = Radio.parseNearby(body, 28.61, 77.21).single().distanceKm
        assertEquals(Radio.distanceKm(28.61, 77.21, 28.7, 77.1), km, 1e-9)
        assertEquals(14.6, km, 0.3)
    }

    @Test fun stationsWithoutAPlaceAreLeftOutOfNearby() {
        assertTrue(Radio.parseNearby(fixture, 28.61, 77.21).all { it.station.latitude != null })
    }

    @Test fun onlyARoughPositionIsSent() {
        assertEquals(28.61, Radio.coarse(28.613456), 0.0)
        assertEquals(-73.98, Radio.coarse(-73.97538), 0.0)
    }

    @Test fun distancesReadNaturally() {
        assertEquals("Under 1 km", Radio.formatDistance(0.19))
        assertEquals("4.2 km", Radio.formatDistance(4.24))
        assertEquals("37 km", Radio.formatDistance(36.6))
        val s = Radio.parseNearby(nearby, 28.61, 77.21).first()
        assertEquals("Under 1 km · AAC+ · 32 kbps · Delhi", s.station.nearbyDetails(s.distanceKm))
    }

    @Test fun frequenciesComeOutOfStationNames() {
        assertEquals("93.5 FM", Radio.frequencyIn("Red FM 93.5"))
        assertEquals("92.7 FM", Radio.frequencyIn("BIG 92.7 FM"))
        assertEquals("91.9 FM", Radio.frequencyIn("Radio Indigo 91.9 FM in Panaji/Bangalore"))
        assertEquals("95 FM", Radio.frequencyIn("95 FM Tadka"))
        assertEquals("104.8 FM", Radio.frequencyIn("Ishq 104,8"))
        assertEquals(null, Radio.frequencyIn("Radio 24"))
        assertEquals(null, Radio.frequencyIn("Radio 88 Jazz"))
        assertEquals(null, Radio.frequencyIn("Top 100 Hits 2024.1"))
        assertEquals(null, Radio.frequencyIn("AIR Delhi FM Gold"))
    }

    @Test fun theFrequencyLeadsAStationsDetails() {
        val s = Radio.Station(uuid = "u", name = "BIG 92.7 FM", streamUrl = "https://x/s", codec = "MP3", bitrate = 128, state = "Jammu and Kashmir")
        assertEquals("92.7 FM · MP3 · 128 kbps · Jammu and Kashmir", s.nearbyDetails(null))
        assertTrue(s.details.startsWith("92.7 FM · MP3"))
    }

    @Test fun onlyStreamAddressesCanBeAdded() {
        assertTrue(Radio.isStreamUrl("https://stream.example.com/live.mp3"))
        assertTrue(Radio.isStreamUrl(" http://1.2.3.4:8000/stream "))
        assertFalse(Radio.isStreamUrl("redfm.in"))
        assertFalse(Radio.isStreamUrl("https://"))
        assertFalse(Radio.isStreamUrl("https://a b.com/x"))
    }

    @Test fun anAddedStationIsAFavouriteThatPlaysAndIsMarkedAsYours() {
        val s = Radio.addCustom("  My FM 93.5 ", "https://stream.example.com/live.m3u8")
        assertTrue(s.custom)
        assertTrue(s.hls)
        assertEquals("My FM 93.5", s.name)
        assertTrue(Radio.isFavourite(s.uuid))
        assertEquals("https://stream.example.com/live.m3u8", Radio.streamUrl(s.id))
        assertTrue(s.details.startsWith("93.5 FM · Added by you"))
        Radio.setFavourite(s, false)
    }
}
