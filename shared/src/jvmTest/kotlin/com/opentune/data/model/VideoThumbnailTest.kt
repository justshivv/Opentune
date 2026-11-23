package com.opentune.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** A YouTube video's thumbnail is asked for at a size that isn't blurred where it's drawn. */
class VideoThumbnailTest {
    private val small = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"

    @Test fun theSmallestThumbnailIsSwappedForHqdefaultWhenMoreIsAsked() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", small.artworkAt(NOTIFICATION_ART_PX))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", small.artworkAt(CARD_ART_PX))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", Song("dQw4w9WgXcQ", "t", "a", small).artworkAt(PLAYER_ART_PX))
    }

    @Test fun aSmallSizeKeepsTheSmallThumbnail() {
        assertEquals(small, small.artworkAt(96))
        assertEquals(small, small.artworkAt(120))
    }

    @Test fun webpAndNumberedHostsKeepTheirForm() {
        assertEquals("https://i.ytimg.com/vi_webp/a-b_c/hqdefault.webp", "https://i.ytimg.com/vi_webp/a-b_c/default.webp".artworkAt(600))
        assertEquals("https://i9.ytimg.com/vi/abc/hqdefault.jpg", "https://i9.ytimg.com/vi/abc/default.jpg".artworkAt(600))
    }

    /** A query was signed for the old name and would fail on the new one. */
    @Test fun aSignedQueryIsDroppedWithTheOldName() {
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", "https://i.ytimg.com/vi/abc/default.jpg?sqp=-oaymwE&rs=AOn4".artworkAt(600))
    }

    /** Never maxresdefault or sddefault, which many videos don't have; mqdefault isn't letterboxed into a 4:3 one. */
    @Test fun largerAndWideThumbnailsAreLeftAsTheyAre() {
        for (name in listOf("mqdefault", "hqdefault", "sddefault", "maxresdefault", "hq720")) {
            val url = "https://i.ytimg.com/vi/abc/$name.jpg?sqp=x"
            assertEquals(url, url.artworkAt(2000))
            assertEquals(url, url.artworkAt(ROW_ART_PX))
        }
    }

    @Test fun otherHostsAreUntouched() {
        assertEquals("https://lh3.googleusercontent.com/x=w600-h600-l90-rj", "https://lh3.googleusercontent.com/x=w60-h60-l90-rj".artworkAt(600))
        assertEquals("https://example.com/vi/abc/default.jpg", "https://example.com/vi/abc/default.jpg".artworkAt(600))
    }
}
