package com.opentune.data

import com.opentune.data.model.ArtistPage
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings

/**
 * "Hide explicit content": songs, albums and cards YouTube Music badges "E"
 * are left out of Home, search, album and artist pages, and out of what
 * autoplay and radio queue. Only what's marked is caught, so a track the
 * catalogue doesn't label still shows.
 */
object ContentFilter {
    val hideExplicit: Boolean get() = AppSettings.library.value.hideExplicit

    fun songs(list: List<Song>, hide: Boolean = hideExplicit): List<Song> =
        if (hide) list.filterNot { it.isExplicit == true } else list

    fun shelves(list: List<HomeShelf>, hide: Boolean = hideExplicit): List<HomeShelf> =
        if (!hide) list else list.map { s -> s.copy(items = s.items.filterNot { it.explicit }) }.filter { it.items.isNotEmpty() }

    fun results(list: List<SearchResult>, hide: Boolean = hideExplicit): List<SearchResult> =
        if (!hide) list else list.filterNot {
            when (it) {
                is SearchResult.Track -> it.song.isExplicit == true
                is SearchResult.TopTrack -> it.song.isExplicit == true
                is SearchResult.Browse -> it.item.explicit
            }
        }

    fun artist(page: ArtistPage, hide: Boolean = hideExplicit): ArtistPage =
        if (!hide) page else page.copy(songs = songs(page.songs, true), sections = shelves(page.sections, true))
}
