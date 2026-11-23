package com.opentune.ui.home

import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import org.junit.Assert.*
import org.junit.Test

class HomeSectionsTest {
    private fun songs(title: String) = HomeShelf(title, (1..4).map {
        ShelfItem("$title $it", "Artist", null, "$title-$it", null)
    })
    private fun collection(title: String, id: String) = HomeShelf(title,
        listOf(ShelfItem(title, "For you", null, null, id)))

    @Test fun historyPicksLeadThenPlaylistsAndVariedCardsWithoutLosingShelves() {
        val generic = songs("Quick picks")
        val related = songs("Because you played Millionaire")
        val more = songs("Your favourites")
        val album = collection("New albums", "MPRE123")
        val playlist = collection("Playlists for you", "VLPL123")
        val input = listOf(generic, related, more, album, playlist)
        val result = arrangeHomeShelves(input)
        assertEquals(related, result.quickPicks)
        assertEquals(listOf(playlist, generic, album, more), result.cards)
        assertEquals(input.toSet(), (listOfNotNull(result.quickPicks) + result.cards).toSet())
    }

    @Test fun songOnlyFeedKeepsJustOneListAndEmptyShelvesAreOmitted() {
        val first = songs("Quick picks")
        val second = songs("Discover")
        val result = arrangeHomeShelves(listOf(HomeShelf("Empty", emptyList()), first, second))
        assertEquals(first, result.quickPicks)
        assertEquals(listOf(second), result.cards)
        assertNull(arrangeHomeShelves(emptyList()).quickPicks)
    }

    @Test fun collectionsStillAppearWithoutSongRecommendations() {
        val playlist = collection("Playlists", "VLPL123")
        val result = arrangeHomeShelves(listOf(playlist))
        assertNull(result.quickPicks)
        assertEquals(listOf(playlist), result.cards)
    }

    @Test fun podcastsTurnedOffLeaveHomeWithoutPodcastRowsOrShows() {
        val music = songs("Quick picks")
        val podcasts = collection("Podcasts for you", "MPSPPLabc")
        val episodes = songs("New episodes")
        val mixed = HomeShelf("Recommended", listOf(
            ShelfItem("Album", "", null, null, "MPRE1"), ShelfItem("Show", "", null, null, "MPSPPLxyz")))
        val input = listOf(music, podcasts, episodes, mixed)
        // On (the default): nothing is taken away.
        assertEquals(input, withoutPodcasts(input, drop = false))
        val off = withoutPodcasts(input, drop = true)
        assertEquals(listOf("Quick picks", "Recommended"), off.map { it.title })
        assertEquals(listOf("MPRE1"), off.last().items.map { it.browseId })
    }
}
