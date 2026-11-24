package com.opentune.tv

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.Song
import com.opentune.i18n.Tr

/*
 * The album, playlist and artist pages: a header with the cover, Play and Shuffle, then the songs
 * as rows (and, for an artist, their albums, singles and similar artists as shelves). Play takes
 * the focus as a page opens; Back puts it on the row or card left from.
 */

/** An album or playlist: its songs under a header with its cover. */
@Composable
fun CollectionScreen(nav: TvNav, dest: Dest.Collection) {
    val (state, retry) = rememberLoad(dest.browseId) { MusicRepository.collection(dest.browseId) }
    val album = remember(dest.browseId) { MusicRepository.typeOf(dest.browseId) == BrowseType.ALBUM }
    val kind = if (album) "Album" else "Playlist"
    when (val s = state) {
        Load.Loading -> DetailLoading(dest.title.orEmpty(), kind, dest.art)
        Load.Failed -> DetailFailed(retry)
        is Load.Done -> {
            val c = s.value
            SongListPage(
                nav, c.title, listOf(c.subtitle, songCount(c.songs.size)).filter { it.isNotBlank() }.joinToString(" · "),
                c.songs, c.title, "Nothing in here yet.", art = c.thumbnailUrl ?: dest.art,
                kind = kind,
                // An album's songs share its cover, so its rows show their numbers instead.
                covers = !album,
            )
        }
    }
}

/** An artist: a face, their top songs, then their albums, singles and similar artists. */
@Composable
fun ArtistScreen(nav: TvNav, dest: Dest.Artist) {
    val (state, retry) = rememberLoad(dest.browseId) { MusicRepository.artist(dest.browseId) }
    when (val s = state) {
        Load.Loading -> DetailLoading(dest.name.orEmpty(), "Artist", null, round = true)
        Load.Failed -> DetailFailed(retry)
        is Load.Done -> {
            val page = s.value
            val name = page.name ?: dest.name.orEmpty()
            val top = remember(page) { page.songs.take(TOP_SONGS) }
            val shelves = remember(page) { page.sections.filter { it.items.isNotEmpty() } }
            SongListPage(
                nav, name, page.subscriberCountText, top, name, "No songs on this page.",
                art = page.thumbnailUrl, round = true, note = page.description, kind = "Artist",
                songsTitle = "Top songs",
                more = {
                    shelves.forEachIndexed { i, shelf ->
                        item(key = "shelf:$i:${shelf.title}") {
                            TvShelf(
                                shelf.title, subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                                // The list's rows sit close; a shelf keeps a section's distance.
                                modifier = Modifier.padding(top = TvSpace.XL - TvSpace.XS),
                            ) {
                                itemsIndexed(shelf.items, key = { j, item -> "$j:${item.browseId ?: item.videoId ?: item.title}" }) { j, item ->
                                    ShelfCard(
                                        item, { nav.openShelfItem(item, name) },
                                        focusKey = "shelf$i/$j:${item.browseId ?: item.videoId ?: item.title}",
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

private const val TOP_SONGS = 8

/** A detail page whose data is on its way: the header from what the card knew, and still rows. */
@Composable
private fun DetailLoading(title: String, kind: String, art: String?, round: Boolean = false) {
    TvPage(scroll = TvScroll.List, spacing = TvSpace.XL) {
        item(key = "header") { TvDetailHeader(title, kind = kind, art = art, round = round) }
        item(key = "loading") { TvLoadingState(kind = TvLoadingKind.Rows) }
    }
}

/** A detail page whose data didn't come: the error, with Try again focused. */
@Composable
private fun DetailFailed(retry: () -> Unit) {
    TvPage(scroll = TvScroll.List) { item(key = "failed") { TvErrorState(retry) } }
}

/**
 * A list of songs under a header, with Play and Shuffle: the liked songs, a playlist, an album, an
 * artist's top songs. [kind] is the small line over the title ("Album"); [covers] false for an
 * album, whose songs share its cover; [songsTitle] a title over the songs; [more] adds items after
 * the songs (an artist's albums and similar artists). Play takes the focus as the page opens.
 */
@Composable
internal fun SongListPage(
    nav: TvNav,
    title: String,
    subtitle: String?,
    songs: List<Song>,
    source: String,
    empty: String,
    art: String? = null,
    round: Boolean = false,
    note: String? = null,
    more: LazyListScope.() -> Unit = {},
    kind: String? = null,
    covers: Boolean = true,
    songsTitle: String? = null,
) {
    val playing by nav.player.currentSong.collectAsState()
    val playingId = playing?.videoId
    TvPage(scroll = TvScroll.List, spacing = TvSpace.XS) {
        item(key = "header") {
            TvDetailHeader(
                title, kind = kind, subtitle = subtitle, note = note, art = art, round = round,
                modifier = Modifier.padding(bottom = TvSpace.XL - TvSpace.XS),
                actions = if (songs.isEmpty()) null else ({
                    TvPillButton(
                        "Play", { nav.playList(songs, 0, source) }, icon = Icons.Rounded.PlayArrow, primary = true,
                        focusKey = "play", entryFocus = true,
                    )
                    TvPillButton("Shuffle", { nav.playList(songs, 0, source, shuffle = true) }, icon = Icons.Rounded.Shuffle, focusKey = "shuffle")
                }),
            )
        }
        if (songs.isEmpty()) item(key = "empty") { TvNote(empty) }
        else {
            if (songsTitle != null) item(key = "songs-title") { TvSectionTitle(songsTitle, Modifier.padding(bottom = TvSpace.S)) }
            itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
                TvSongRow(
                    title = song.title, artist = song.artist, duration = song.durationText,
                    imageUrl = if (covers) song.thumbnailUrl else null,
                    onClick = { nav.playList(songs, i, source) },
                    index = i + 1, playing = song.videoId == playingId, showCover = covers,
                    trailing = if (song.isExplicit == true) "E" else null,
                    focusKey = "song/$i:${song.videoId}",
                    modifier = Modifier.safeSides(),
                )
            }
        }
        more()
    }
}
