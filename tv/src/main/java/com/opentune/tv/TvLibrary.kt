package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.history.History
import com.opentune.data.library.LibraryCache
import com.opentune.data.library.LibraryStore
import com.opentune.i18n.Tr

/* The Library tab and its pages: Liked songs, and a playlist made on the TV. */

/** "1 song", "12 songs", in the TV's language. */
internal fun songCount(n: Int): String = if (n == 1) Tr.t("1 song") else Tr.f("{0} songs", n)

/**
 * The Library tab, as rows of cards: your music (Liked songs and the playlists made on this TV),
 * your YouTube Music playlists (or a way to sign in for them), and what you played lately.
 * It needs no network, so it opens at once with the focus on Liked songs.
 */
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

    TvPage {
        item(key = "mine") {
            TvShelf("Your music") {
                item(key = "liked") {
                    TvIconCard(
                        "Liked songs", songCount(liked.size), Icons.Rounded.Favorite, { nav.open(Dest.Liked) },
                        color = LikedTile, focusKey = "mine/liked",
                    )
                }
                itemsIndexed(local, key = { _, p -> "local:${p.id}" }) { i, p ->
                    TvCard(
                        p.name, songCount(p.songs.size), p.songs.firstOrNull()?.thumbnailUrl, { nav.open(Dest.Local(p.id)) },
                        focusKey = "mine/$i:${p.id}",
                    )
                }
            }
        }
        if (remote.isNotEmpty()) item(key = "remote") {
            TvShelf("YouTube Music playlists") {
                itemsIndexed(remote, key = { i, it -> "$i:${it.browseId ?: it.title}" }) { i, item ->
                    ShelfCard(item, { nav.openShelfItem(item, "Library") }, focusKey = "remote/$i:${item.browseId ?: item.title}")
                }
            }
        } else if (!signedIn) item(key = "signin") {
            // Under the first card, so Down from Your music reaches the button.
            Column(Modifier.fillMaxWidth().safeSides(), verticalArrangement = Arrangement.spacedBy(TvSpace.XS)) {
                Text("YouTube Music playlists", style = TvType.Title)
                Text("Sign in from Settings to see your YouTube Music playlists here.", style = TvType.Body, color = TvColors.TextDim)
                Spacer(Modifier.height(TvSpace.M))
                TvPillButton("Sign in", { nav.goTab(Dest.Settings) }, icon = Icons.AutoMirrored.Rounded.Login, focusKey = "signin")
            }
        }
        if (recents.isNotEmpty()) item(key = "recents") {
            TvShelf("Recently played") {
                itemsIndexed(recents, key = { _, s -> s.videoId }) { i, song ->
                    SongCard(song, { nav.play(song, "Library") }, focusKey = "recent/$i:${song.videoId}")
                }
            }
        }
    }
}

/** Liked songs' cover: a muted rose, so the white heart reads and it isn't mistaken for the accent. */
private val LikedTile = TvColors.Tiles[2]

@Composable
fun LikedScreen(nav: TvNav) {
    val liked by LibraryStore.liked.collectAsState()
    val songs = remember(liked) { liked.map { it.toSong() } }
    SongListPage(nav, "Liked songs", songCount(songs.size), songs, "Liked songs", "Press the heart while a song plays to keep it here.", art = songs.firstOrNull()?.thumbnailUrl)
}

@Composable
fun LocalPlaylistScreen(nav: TvNav, id: String) {
    val lists by LibraryStore.playlists.collectAsState()
    val list = lists.firstOrNull { it.id == id }
    val songs = remember(list) { list?.songs?.map { it.toSong() }.orEmpty() }
    SongListPage(nav, list?.name ?: "Playlist", songCount(songs.size), songs, list?.name ?: "Playlist", "This playlist is empty.", art = songs.firstOrNull()?.thumbnailUrl)
}
