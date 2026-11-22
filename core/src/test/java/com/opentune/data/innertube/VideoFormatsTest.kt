package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatsTest {
    private fun response(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun parsesMuxedAndAdaptiveAndDropsAudioAndUnusable() {
        val list = StreamResolver.videoFormatList(response("""
            {"streamingData": {
              "formats": [
                {"itag": 18, "url": "https://x/18", "mimeType": "video/mp4; codecs=\"avc1, mp4a\"", "bitrate": 512000, "width": 640, "height": 360}
              ],
              "adaptiveFormats": [
                {"itag": 251, "url": "https://x/251", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000},
                {"itag": 137, "signatureCipher": "s=abc", "mimeType": "video/mp4; codecs=\"avc1\"", "bitrate": 4500000, "width": 1920, "height": 1080},
                {"itag": 136, "mimeType": "video/mp4; codecs=\"avc1\"", "bitrate": 2200000, "width": 1280, "height": 720}
              ]
            }}
        """.trimIndent()))
        // The 720p entry has neither a URL nor a cipher: dropped, like audio entries are.
        assertEquals(2, list.size)
        assertEquals(listOf(360, 1080), list.map { it.height })
        assertTrue(list[0].muxed)
        assertTrue(!list[1].muxed)
    }

    @Test fun ranksMuxedFirstThenTallest() {
        val ranked = StreamResolver.rankVideoFormats(response("""
            {"streamingData": {
              "formats": [
                {"itag": 18, "url": "https://x/18", "mimeType": "video/mp4", "bitrate": 512000, "width": 640, "height": 360}
              ],
              "adaptiveFormats": [
                {"itag": 136, "url": "https://x/136", "mimeType": "video/mp4", "bitrate": 2200000, "width": 1280, "height": 720},
                {"itag": 137, "url": "https://x/137", "mimeType": "video/mp4", "bitrate": 4500000, "width": 1920, "height": 1080}
              ]
            }}
        """.trimIndent()))
        // Muxed wins over taller adaptive: it plays through the single-stream pipeline.
        assertEquals(listOf(360, 1080, 720), ranked.map { it.height })
    }

    @Test fun noStreamingDataMeansNoVideo() {
        assertTrue(StreamResolver.videoFormatList(response("""{"videoDetails": {"videoId": "x"}}""")).isEmpty())
        assertTrue(StreamResolver.rankVideoFormats(response("""{}""")).isEmpty())
    }
}
