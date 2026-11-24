package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.history.History
import com.opentune.data.library.LibraryCache
import com.opentune.data.library.LibraryStore

/* The Library tab and its pages (Liked songs, a playlist made on the TV). Not yet in the new design. */

@Composable
fun LibraryScreen(nav: TvNav) {
    val signedIn by AccountStore.signedIn.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val local by LibraryStore.playlists.collectAsState()
    val remote by LibraryCache.playlists.collectAsState()
    val records by History.records.collectAsState()
    val recents = remember(records) { History.recents(records, 30) }
    // The account's playlists: what was kept shows at once, and this brings them up to date.
    LaunchedEffect(signedIn) { if (signedIn) runCatching { MusicRepository.libraryPlaylists() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "liked") {
            TvShelf("Your music") {
                item(key = "liked") {
                    TvCard("Liked songs", "${liked.size} songs", liked.firstOrNull()?.thumbnailUrl, { nav.open(Dest.Liked) })
                }
                items(local, key = { "local:${it.id}" }) { p ->
                    TvCard(p.name, "${p.songs.size} songs", p.songs.firstOrNull()?.thumbnailUrl, { nav.open(Dest.Local(p.id)) })
                }
            }
        }
        if (remote.isNotEmpty()) item(key = "remote") {
            TvShelf("YouTube Music playlists") {
                items(remote, key = { it.browseId ?: it.title }) { item -> ShelfCard(item, { nav.openShelfItem(item, "Library") }) }
            }
        } else if (!signedIn) item(key = "signin") {
            TvNote("Sign in from Settings to see your YouTube Music playlists here.")
        }
        if (recents.isNotEmpty()) item(key = "recents") {
            TvShelf("Recently played") {
                items(recents, key = { it.videoId }) { song -> SongCard(song, { nav.play(song, "Library") }) }
            }
        }
    }
}

@Composable
fun LikedScreen(nav: TvNav) {
    val liked by LibraryStore.liked.collectAsState()
    val songs = remember(liked) { liked.map { it.toSong() } }
    SongListPage(nav, "Liked songs", "${songs.size} songs", songs, "Liked songs", "Press the heart while a song plays to keep it here.", art = songs.firstOrNull()?.thumbnailUrl)
}

@Composable
fun LocalPlaylistScreen(nav: TvNav, id: String) {
    val lists by LibraryStore.playlists.collectAsState()
    val list = lists.firstOrNull { it.id == id }
    val songs = remember(list) { list?.songs?.map { it.toSong() }.orEmpty() }
    SongListPage(nav, list?.name ?: "Playlist", "${songs.size} songs", songs, list?.name ?: "Playlist", "This playlist is empty.", art = songs.firstOrNull()?.thumbnailUrl)
}
