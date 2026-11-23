package com.opentune.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's shelves of music videos and live performances, from a page built
 * round YouTube Music's own "New music videos" carousel (trimmed), with a
 * live shelf titled in German, an album shelf and a shelf of songs named after videos.
 */
class HomeVideoShelfTest {
    private val shelves = InnertubeParser.parseHome(
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/innertube/home-video-shelf.json")!!.bufferedReader().use { it.readText() },
        ).jsonObject,
    ).associateBy { it.title }

    @Test
    fun musicVideosAreKeptAsAVideoShelf() {
        val shelf = shelves.getValue("New music videos")
        assertTrue(shelf.videos)
        assertEquals(4, shelf.items.size)
        val first = shelf.items.first()
        assertEquals("Wy5H0oeWtSE", first.videoId)
        assertNull("a video plays, it opens no page", first.browseId)
        assertTrue(first.video)
        assertEquals("longleggss • 14K views", first.subtitle)
    }

    @Test
    fun theFrameIsTheFullWidescreenOne() {
        // YouTube offers hq720 signed down to 800x450; unsigned it's the 1280x720 frame.
        val first = shelves.getValue("New music videos").items.first()
        assertEquals("https://i.ytimg.com/vi/Wy5H0oeWtSE/hq720.jpg", first.thumbnailUrl)
        // With only a small wide frame offered, that one.
        val live = shelves.getValue("Auftritte live").items
        assertEquals("https://i.ytimg.com/vi/liveBBBBBBB/mqdefault.jpg", live[1].thumbnailUrl)
    }

    @Test
    fun liveShowsAreKnownByTheirCardsInAnyLanguage() {
        // No aspect hint here: uploads with widescreen frames.
        val shelf = shelves.getValue("Auftritte live")
        assertTrue(shelf.videos)
        assertEquals(listOf("liveAAAAAAA", "liveBBBBBBB"), shelf.items.map { it.videoId })
    }

    @Test
    fun songsNamedLikeVideosAndAlbumsStaySquare() {
        val songs = shelves.getValue("Mehr wie das")
        assertFalse(songs.videos)
        assertEquals(listOf("songAAAAAAA", "songBBBBBBB"), songs.items.map { it.videoId })
        assertFalse(songs.items.any { it.video })
        val albums = shelves.getValue("Albums for you")
        assertFalse(albums.videos)
        assertEquals(listOf("MPREb_albumAAAAA", "MPREb_albumBBBBB"), albums.items.map { it.browseId })
    }

    @Test
    fun aShelfMostlyOfSongsIsNotAVideoShelf() {
        val page = Json.parseToJsonElement(
            """
            {"contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
              {"musicCarouselShelfRenderer": {
                "header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Mixed"}]}}},
                "contents": [
                  {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "A"}]}, "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
                    "navigationEndpoint": {"watchEndpoint": {"videoId": "aaaaaaaaaaa"}}}},
                  {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "B"}]}, "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
                    "navigationEndpoint": {"watchEndpoint": {"videoId": "bbbbbbbbbbb"}}}},
                  {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "C"}]}, "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
                    "navigationEndpoint": {"watchEndpoint": {"videoId": "ccccccccccc"}}}}
                ]
              }}
            ]}}}}]}}}
            """,
        ).jsonObject
        val shelf = InnertubeParser.parseHome(page).single()
        assertFalse(shelf.videos)
    }
}
