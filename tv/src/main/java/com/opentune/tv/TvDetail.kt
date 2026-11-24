package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.Song

/** An album or playlist: its songs under a header with its cover. */
@Composable
fun CollectionScreen(nav: TvNav, dest: Dest.Collection) {
    val (state, retry) = rememberLoad(dest.browseId) { MusicRepository.collection(dest.browseId) }
    when (val s = state) {
        Load.Loading -> SongListPage(nav, dest.title.orEmpty(), "Loading…", emptyList(), dest.title ?: "Playlist", "", art = dest.art)
        Load.Failed -> TvErrorState(retry)
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
        Load.Failed -> TvErrorState(retry)
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

/**
 * A list of songs under a header, with Play and Shuffle: the liked songs, a playlist, an album, an
 * artist's top songs. [more] adds rows after the songs (an artist's albums and similar artists).
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
    more: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    val playing by nav.player.currentSong.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "header") {
            Row(Modifier.padding(horizontal = TvSafe.Horizontal, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                if (art != null) Cover(art, Modifier.size(280.dp), if (round) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(18.dp), px = TvArt.Large)
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
                    if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (songs.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvPillButton("Play", { nav.playList(songs, 0, source) }, icon = Icons.Rounded.PlayArrow, primary = true)
                        TvPillButton("Shuffle", { nav.playList(songs, 0, source, shuffle = true) }, icon = Icons.Rounded.Shuffle)
                    }
                }
            }
        }
        if (songs.isEmpty()) item(key = "empty") { TvNote(empty) }
        else itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            TvSongRow(
                title = song.title, artist = song.artist, duration = song.durationText, imageUrl = null,
                onClick = { nav.playList(songs, i, source) },
                index = i + 1, playing = playing?.videoId == song.videoId, showCover = false,
                focusKey = "song/$i:${song.videoId}",
                modifier = Modifier.padding(horizontal = TvSafe.Horizontal - 8.dp),
            )
        }
        more()
    }
}
