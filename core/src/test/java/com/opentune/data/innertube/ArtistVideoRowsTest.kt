package com.opentune.data.innertube

import com.opentune.data.HomeVideos
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An artist page's rows of music videos and live performances (a real page, trimmed to four
 * cards a row), and Home's own video rows made from such pages.
 */
class ArtistVideoRowsTest {
    private val page = InnertubeParser.parseArtistPage(
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/innertube/artist-video-rows.json")!!.bufferedReader().use { it.readText() },
        ).jsonObject,
    )

    @Test
    fun theVideoRowsAreKeptAsWideCards() {
        assertEquals(listOf("Videos", "Live performances"), page.videoShelves.map { it.title })
        page.videoShelves.forEach { row ->
            assertTrue(row.videos)
            assertEquals(4, row.items.size)
            assertTrue(row.items.all { it.video && it.videoId != null && it.browseId == null })
        }
    }

    @Test
    fun theOtherRowsStayAsTheyWere() {
        assertEquals(listOf("Playlists by Arijit Singh"), page.sections.map { it.title })
        assertFalse(page.sections.any { it.videos })
    }

    private fun card(id: String) = ShelfItem(id, "Artist", null, id, null, video = true)
    private fun artist(videos: List<String>, live: List<String> = emptyList()) = ArtistPage(
        emptyList(), null, emptyList(),
        videoShelves = listOfNotNull(
            HomeShelf("Videos", videos.map(::card), videos = true),
            live.takeIf { it.isNotEmpty() }?.let { HomeShelf("Live", it.map(::card), videos = true) },
        ),
    )

    @Test
    fun theArtistsTakeTurns() {
        val rows = HomeVideos.shelves(listOf(artist(listOf("a1", "a2", "a3")), artist(listOf("b1", "b2"))), "Music videos for you", "Live performances")
        assertEquals(1, rows.size)
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), rows.single().items.map { it.videoId })
        assertTrue(rows.single().videos)
    }

    @Test
    fun aVideoShowsOnceAndARowNeedsThreeCards() {
        val rows = HomeVideos.shelves(
            listOf(artist(listOf("a1", "a2", "a3"), live = listOf("a1", "l1")), artist(listOf("a2", "b1"), live = listOf("l2"))),
            "Music videos for you", "Live performances",
        )
        assertEquals(listOf("Music videos for you"), rows.map { it.title })
        assertEquals(listOf("a1", "a2", "b1", "a3"), rows.single().items.map { it.videoId })
    }

    @Test
    fun aRowStopsAtTwentyCards() {
        val many = (1..10).map { n -> artist((1..5).map { "v$n-$it" }, live = (1..5).map { "l$n-$it" }) }
        val rows = HomeVideos.shelves(many, "V", "L")
        assertEquals(listOf(HomeVideos.MAX, HomeVideos.MAX), rows.map { it.items.size })
    }

    @Test
    fun theRealPageMakesBothRows() {
        val rows = HomeVideos.shelves(listOf(page), "Music videos for you", "Live performances")
        assertEquals(listOf("Music videos for you", "Live performances"), rows.map { it.title })
    }

    private fun row(title: String, videos: Boolean = false) = HomeShelf(title, listOf(card(title)), videos = videos)

    @Test
    fun videoRowsNeverSitOneUnderTheOther() {
        val home = listOf(row("Radio"), row("Videos", true), row("Live", true), row("Albums"), row("Charts"))
        assertEquals(listOf("Radio", "Videos", "Albums", "Live", "Charts"), HomeVideos.spacedApart(home).map { it.title })
        // Nor under a video row at the end of the first page.
        val after = HomeVideos.spacedApart(listOf(row("Videos", true), row("Albums")), after = row("Their videos", true))
        assertEquals(listOf("Albums", "Videos"), after.map { it.title })
        // Other rows keep their order.
        val plain = listOf(row("A"), row("B"), row("C"))
        assertEquals(plain, HomeVideos.spacedApart(plain))
    }

    @Test
    fun aSpelledOutLengthIsNoArtist() {
        assertEquals("", InnertubeParser.artistFromSubtitle("2 min 59 sec"))
        assertEquals("Mohit Chauhan", InnertubeParser.artistFromSubtitle("Mohit Chauhan • 3 min 2 sec"))
        assertEquals("50 Cent", InnertubeParser.artistFromSubtitle("50 Cent • 2.1M views"))
    }
}
