package com.opentune.data.releases

import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewReleasesTest {
    private fun album(id: String) = ShelfItem(id, "2026", null, null, id)

    private val page = ArtistPage(
        songs = emptyList(),
        moreSongsBrowseId = null,
        sections = listOf(
            HomeShelf("Albums", listOf(album("MPREb_new"), album("MPREb_old"))),
            HomeShelf("Singles & EPs", listOf(album("MPREb_single"))),
            HomeShelf("Fans might also like", listOf(ShelfItem("Other", "", null, null, "UCother"))),
            HomeShelf("Featured on", listOf(album("VLPLsomething"))),
        ),
        name = "Artist",
    )

    @Test fun releasesAreAlbumsAndSinglesOnly() {
        assertEquals(listOf("MPREb_new", "MPREb_old", "MPREb_single"), NewReleases.releasesOf(page).map { it.browseId })
    }

    @Test fun announcesOnlyWhatWasntSeen() {
        val artist = NewReleases.Followed("UCx", "Artist", known = setOf("MPREb_old", "MPREb_single"))
        val (fresh, known) = NewReleases.diff(artist, NewReleases.releasesOf(page))
        assertEquals(listOf("MPREb_new"), fresh.map { it.browseId })
        assertEquals(setOf("MPREb_new", "MPREb_old", "MPREb_single"), known)
    }

    @Test fun aFirstLookAnnouncesNothing() {
        val (fresh, known) = NewReleases.diff(NewReleases.Followed("UCx", "Artist"), NewReleases.releasesOf(page))
        assertTrue(fresh.isEmpty())
        assertEquals(3, known.size)
    }
}
