package com.opentune.tv

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import com.opentune.data.MusicRepository

/** An album or playlist: its songs under a header with its cover. */
@Composable
fun CollectionScreen(nav: TvNav, dest: Dest.Collection) {
    val (state, retry) = rememberLoad(dest.browseId) { MusicRepository.collection(dest.browseId) }
    when (val s = state) {
        Load.Loading -> SongListPage(nav, dest.title.orEmpty(), "Loading…", emptyList(), dest.title ?: "Playlist", "", art = dest.art)
        Load.Failed -> LoadFailed(dest.title ?: "this page", retry)
        is Load.Done -> {
            val c = s.value
            SongListPage(
                nav, c.title, listOf(c.subtitle, "${c.songs.size} songs").filter { it.isNotBlank() }.joinToString(" · "),
                c.songs, c.title, "Nothing in here yet.", art = c.thumbnailUrl ?: dest.art,
            )
        }
    }
}

/** An artist: a face, their top songs, then their albums, singles and similar artists. */
@Composable
fun ArtistScreen(nav: TvNav, dest: Dest.Artist) {
    val (state, retry) = rememberLoad(dest.browseId) { MusicRepository.artist(dest.browseId) }
    when (val s = state) {
        Load.Loading -> SongListPage(nav, dest.name.orEmpty(), "Loading…", emptyList(), dest.name ?: "Artist", "", round = true)
        Load.Failed -> LoadFailed(dest.name ?: "this artist", retry)
        is Load.Done -> {
            val page = s.value
            val name = page.name ?: dest.name.orEmpty()
            SongListPage(
                nav, name, page.subscriberCountText, page.songs.take(TOP_SONGS), name, "No songs on this page.",
                art = page.thumbnailUrl, round = true, note = page.description,
                more = {
                    page.sections.filter { it.items.isNotEmpty() }.forEachIndexed { i, shelf ->
                        item(key = "shelf:$i:${shelf.title}") {
                            TvShelf(shelf.title, subtitle = shelf.subtitle.takeIf { it.isNotBlank() }) {
                                items(shelf.items, key = null) { item -> ShelfCard(item, { nav.openShelfItem(item, name) }) }
                            }
                        }
                    }
                },
            )
        }
    }
}

private const val TOP_SONGS = 8

