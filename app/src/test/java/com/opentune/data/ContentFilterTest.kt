package com.opentune.data

import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentFilterTest {
    private val clean = Song("clean000001", "Clean", "A", null, isExplicit = false)
    private val unknown = Song("unknown0001", "Unknown", "A", null)
    private val explicit = Song("explicit001", "Explicit", "A", null, isExplicit = true)

    @Test fun hidesOnlyMarkedSongs() {
        assertEquals(listOf(clean, unknown), ContentFilter.songs(listOf(clean, explicit, unknown), hide = true))
        assertEquals(3, ContentFilter.songs(listOf(clean, explicit, unknown), hide = false).size)
    }

    @Test fun dropsShelvesLeftEmpty() {
        val e = ShelfItem("E", "", null, "explicit001", null, explicit = true)
        val c = ShelfItem("C", "", null, "clean000001", null)
        val shelves = listOf(HomeShelf("Mixed", listOf(e, c)), HomeShelf("All explicit", listOf(e)))
        val out = ContentFilter.shelves(shelves, hide = true)
        assertEquals(listOf("Mixed"), out.map { it.title })
        assertEquals(listOf(c), out.single().items)
    }

    @Test fun filtersSearchRowsOfEveryKind() {
        val rows = listOf(
            SearchResult.TopTrack(explicit),
            SearchResult.Track(clean),
            SearchResult.Browse(BrowseItem("MPREb_x", "Album", "", null, BrowseType.ALBUM, explicit = true)),
            SearchResult.Browse(BrowseItem("UCx", "Artist", "", null, BrowseType.ARTIST)),
        )
        assertEquals(2, ContentFilter.results(rows, hide = true).size)
    }
}
