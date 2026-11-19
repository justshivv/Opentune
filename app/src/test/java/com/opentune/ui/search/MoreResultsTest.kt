package com.opentune.ui.search

import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class MoreResultsTest {
    private fun track(id: String) = SearchResult.Track(Song(id, "Song $id", "Artist", null))
    private fun album(id: String) = SearchResult.Browse(BrowseItem(id, "Album $id", "Artist", null, BrowseType.ALBUM))

    @Test fun aLaterPageAddsOnlyWhatIsntShownYet() {
        val shown = listOf(track("a"), track("b"), album("MPREx"))
        val page = listOf(track("b"), track("c"), album("MPREx"), album("MPREy"))
        assertEquals(listOf(track("c"), album("MPREy")), withoutShown(shown, page))
    }

    @Test fun theTopResultCountsAsShownForItsSong() {
        val shown = listOf(SearchResult.TopTrack(Song("a", "Song a", "Artist", null)))
        assertEquals(listOf(track("d")), withoutShown(shown, listOf(track("a"), track("d"))))
    }

    @Test fun aRowTwiceOnOnePageIsAddedOnce() {
        assertEquals(listOf(track("e")), withoutShown(emptyList(), listOf(track("e"), track("e"))))
    }
}
