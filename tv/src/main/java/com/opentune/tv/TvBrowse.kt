package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentune.data.ExploreFeed
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.history.History
import com.opentune.data.library.LibraryCache
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException

/** What a page's data is doing: on its way, here, or not coming. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/** [block]'s answer as a [Load], read again when [key] changes or the returned retry is called. */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, key, attempt) {
        value = Load.Loading
        value = try { Load.Done(block()) } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }
    return state to { attempt++ }
}

/** The line and button a page shows when its data didn't come. */
@Composable
fun LoadFailed(what: String, retry: () -> Unit) {
    Column(Modifier.padding(horizontal = TvSafe.Horizontal, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Couldn't load $what. Check the connection.", style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
        TvButton("Try again", retry)
    }
}

@Composable
fun SongCard(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier) =
    TvCard(song.title, song.artist, song.thumbnailUrl, onClick, modifier)

@Composable
fun ShelfCard(item: ShelfItem, onClick: () -> Unit, modifier: Modifier = Modifier) =
    TvCard(item.title, item.subtitle, item.thumbnailUrl, onClick, modifier, round = item.browseId?.startsWith("UC") == true)

/** A feed's shelves, one row each; the cards open pages or play. */
private fun androidx.compose.foundation.lazy.LazyListScope.shelves(list: List<HomeShelf>, nav: TvNav, from: String) {
    itemsIndexed(list.filter { it.items.isNotEmpty() }, key = { i, s -> "$i:${s.title}" }) { _, shelf ->
        TvShelf(shelf.title, subtitle = shelf.subtitle.takeIf { it.isNotBlank() }) {
            items(shelf.items, key = null) { item -> ShelfCard(item, { nav.openShelfItem(item, from) }) }
        }
    }
}

@Composable
fun HomeScreen(nav: TvNav) {
    val (state, retry) = rememberLoad("home") { MusicRepository.home() }
    val more by MusicRepository.homeMore.collectAsState()
    val records by History.records.collectAsState()
    val recents = remember(records) { History.recents(records, 16) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (recents.isNotEmpty()) item(key = "recents") {
            TvShelf("Listen again") {
                items(recents, key = { it.videoId }) { song -> SongCard(song, { nav.play(song, "Home") }) }
            }
        }
        when (val s = state) {
            Load.Loading -> if (recents.isEmpty()) item(key = "loading") { TvNote("Loading your music…") }
            Load.Failed -> item(key = "failed") { LoadFailed("Home", retry) }
            is Load.Done -> {
                shelves(s.value, nav, "Home")
                shelves(more, nav, "Home")
            }
        }
    }
}

@Composable
fun ExploreScreen(nav: TvNav) {
    val (state, retry) = rememberLoad("explore") { ExploreFeed.load() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val s = state) {
            Load.Loading -> item { TvNote("Loading…") }
            Load.Failed -> item { LoadFailed("Explore", retry) }
            is Load.Done -> {
                val canvas = s.value
                if (canvas.artists.isNotEmpty()) item(key = "artists") {
                    TvShelf("Artists") {
                        items(canvas.artists, key = { it.browseId }) { a ->
                            TvCard(a.name, null, a.thumbnailUrl, { nav.open(Dest.Artist(a.browseId, a.name)) }, round = true)
                        }
                    }
                }
                // One row for each kind, in the order a listener looks for them.
                listOf(ExploreFeed.Kind.NEW, ExploreFeed.Kind.CHART, ExploreFeed.Kind.MOOD, ExploreFeed.Kind.GENRE).forEach { kind ->
                    val tiles = canvas.tiles.filter { it.kind == kind }
                    if (tiles.isNotEmpty()) item(key = "kind:$kind") {
                        TvShelf(kind.label) {
                            items(tiles, key = { it.key }) { tile ->
                                when (tile) {
                                    is ExploreFeed.Tile.Mood -> TvCard(
                                        tile.mood.title, null, tile.mood.thumbnailUrl,
                                        { nav.open(Dest.Mood(tile.mood.browseId, tile.mood.params, tile.mood.title)) },
                                    )
                                    is ExploreFeed.Tile.Item -> ShelfCard(tile.item, { nav.openShelfItem(tile.item, "Explore") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A mood or genre: the shelves YouTube Music keeps for it. */
@Composable
fun MoodScreen(nav: TvNav, mood: Dest.Mood) {
    val (state, retry) = rememberLoad(mood) { MusicRepository.shelves(mood.browseId, mood.params) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle(mood.title) }
        when (val s = state) {
            Load.Loading -> item { TvNote("Loading…") }
            Load.Failed -> item { LoadFailed(mood.title, retry) }
            is Load.Done -> shelves(s.value, nav, mood.title)
        }
    }
}

@Composable
fun PageTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = TvSafe.Horizontal)) {
        Text(text, style = MaterialTheme.typography.headlineMedium)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextDim)
    }
}

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
                if (art != null) Cover(art, Modifier.size(280.dp), if (round) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = TvColors.TextDim)
                    if (!note.isNullOrBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = TvColors.TextDim, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (songs.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvButton("Play", { nav.playList(songs, 0, source) }, icon = Icons.Rounded.PlayArrow, primary = true)
                        TvButton("Shuffle", { nav.playList(songs, 0, source, shuffle = true) }, icon = Icons.Rounded.Shuffle)
                    }
                }
            }
        }
        if (songs.isEmpty()) item(key = "empty") { TvNote(empty) }
        else itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            TvTrackRow(
                number = i + 1, title = song.title, subtitle = song.artist, length = song.durationText,
                onClick = { nav.playList(songs, i, source) },
                playing = playing?.videoId == song.videoId,
                modifier = Modifier.padding(horizontal = TvSafe.Horizontal - 8.dp),
            )
        }
        more()
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

