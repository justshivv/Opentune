package com.opentune.data

import com.opentune.data.model.HomeShelf
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.MoodGenreSection
import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExploreFeedTest {
    @Test fun weavesListsInTurn() {
        assertEquals(listOf(1, "a", 2, "b", 3, 4), ExploreFeed.weave(listOf(listOf(1, 2, 3, 4), listOf("a", "b"), emptyList())))
    }

    private val releases = listOf(HomeShelf("New albums", listOf(ShelfItem("GNX", "Album", null, null, "MPRE1"))))
    private val charts = listOf(
        HomeShelf("Top artists", listOf(ShelfItem("Artist", "", null, null, "UC1"))),
        HomeShelf("Top playlists", listOf(ShelfItem("Top 100", "", null, null, "VLPL1"))),
    )

    @Test fun moodsPageThatFailsLeavesTheRestOfTheBoard() {
        // YouTube's moods page answering 404 must not leave Explore blank.
        val canvas = ExploreFeed.assemble(Result.failure(RuntimeException("HTTP 404")), Result.success(releases), Result.success(charts))
        assertEquals(listOf(ExploreFeed.Kind.NEW, ExploreFeed.Kind.CHART), canvas.tiles.map { it.kind })
        assertEquals(listOf("UC1"), canvas.artists.map { it.browseId })
    }

    @Test fun everyPartFailingIsAnErrorToRetry() {
        val error = RuntimeException("offline")
        val thrown = assertThrows(RuntimeException::class.java) {
            ExploreFeed.assemble(Result.failure(error), Result.failure(RuntimeException()), Result.failure(RuntimeException()))
        }
        assertEquals(error, thrown)
    }

    @Test fun moodsAndGenresAreSortedByTheirSection() {
        val moods = listOf(
            MoodGenreSection("Moods & moments", listOf(MoodGenre("Chill", "FEmusic_moods_and_genres_category", "a"))),
            MoodGenreSection("Genres", listOf(MoodGenre("Haryanvi", "FEmusic_moods_and_genres_category", "b"))),
        )
        val canvas = ExploreFeed.assemble(Result.success(moods), Result.failure(RuntimeException()), Result.failure(RuntimeException()))
        assertEquals(listOf(ExploreFeed.Kind.MOOD, ExploreFeed.Kind.GENRE), canvas.tiles.map { it.kind })
    }
}
