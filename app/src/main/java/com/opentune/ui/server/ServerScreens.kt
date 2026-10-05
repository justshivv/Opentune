package com.opentune.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.model.BrowseType
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.subsonic.Subsonic
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.Shelf
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.components.SongListItem
import com.opentune.ui.library.PlayButtons
import com.opentune.ui.rememberLoader
import com.opentune.ui.settings.ServerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the server pages lead. */
class ServerNav(
    val album: (String) -> Unit,
    val artist: (String) -> Unit,
    val playlist: (String) -> Unit,
    val artists: () -> Unit,
)

private class ServerHome(
    val newest: List<Subsonic.Album>,
    val frequent: List<Subsonic.Album>,
    val recent: List<Subsonic.Album>,
    val playlists: List<Subsonic.Playlist>,
)

/** Your own server's front page: search, new and favourite albums, playlists, artists. */
@Composable
fun ServerScreen(contentPadding: PaddingValues, actions: SongActions, nav: ServerNav, onBack: () -> Unit) {
    val server by Subsonic.server.collectAsState()
    var connecting by remember { mutableStateOf(false) }
    if (connecting) ServerDialog(onDismiss = { connecting = false })
    val current = server
    if (current == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            PageHeader("Your music server", onBack = onBack)
            MessageState(
                Icons.Rounded.Dns,
                "No server connected",
                Modifier.padding(top = 24.dp),
                message = "Stream your own FLAC and hi-res files from Navidrome, Gonic, Airsonic, Nextcloud Music or any Subsonic server.",
                action = { Button(onClick = { connecting = true }) { Text("Connect a server") } },
            )
        }
        return
    }

    val loader = rememberLoader("server:home:${current.url}:${current.user}") {
        ServerHome(
            newest = Subsonic.albums("newest"),
            frequent = runCatching { Subsonic.albums("frequent", 20) }.getOrDefault(emptyList()),
            recent = runCatching { Subsonic.albums("recent", 20) }.getOrDefault(emptyList()),
            playlists = runCatching { Subsonic.playlists() }.getOrDefault(emptyList()),
        )
    }
    val state by loader.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Subsonic.Results?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(300)
        results = runCatching { Subsonic.search(query.trim()) }.getOrNull()
    }
    val scope = rememberCoroutineScope()

    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            PageHeader("Your music server", onBack = onBack, actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Icons.Rounded.Shuffle, "Shuffle the whole server", {
                        scope.launch {
                            runCatching { Subsonic.randomSongs(100) }.getOrNull()?.takeIf { it.isNotEmpty() }
                                ?.let { actions.playAll(it, 0, false, "Your server") }
                        }
                    })
                    GlassIconButton(Icons.AutoMirrored.Rounded.Logout, "Disconnect", { Subsonic.disconnect() })
                }
            })
        }
        item {
            Text(
                listOfNotNull(current.url.removePrefix("https://").removePrefix("http://"), current.type?.replaceFirstChar { it.uppercase() }, current.user).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search your server") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        val found = results
        if (found != null) {
            if (found.albums.isNotEmpty()) item(key = "s-albums") { AlbumShelf("Albums", found.albums, nav) }
            if (found.artists.isNotEmpty()) item(key = "s-artists") { ArtistShelf("Artists", found.artists, nav) }
            if (found.songs.isNotEmpty()) {
                item(key = "s-songs") { SectionHeader("Songs") }
                songRows(found.songs, actions, "Search")
            }
            if (found.albums.isEmpty() && found.artists.isEmpty() && found.songs.isEmpty()) {
                item { MessageState(Icons.Rounded.Search, "Nothing found", message = "Nothing on your server matches \"$query\".") }
            }
            return@LazyColumn
        }
        when (val s = state) {
            is UiState.Loading -> items(3) { ShelfPlaceholder() }
            is UiState.Error -> item { ErrorState(s.message, onRetry = { loader.reload() }, modifier = Modifier.padding(top = 24.dp)) }
            is UiState.Success -> {
                val home = s.data
                if (home.newest.isNotEmpty()) item(key = "newest") { AlbumShelf("Recently added", home.newest, nav) }
                if (home.frequent.isNotEmpty()) item(key = "frequent") { AlbumShelf("Most played", home.frequent, nav) }
                if (home.recent.isNotEmpty()) item(key = "recent") { AlbumShelf("Played lately", home.recent, nav) }
                item(key = "artists") { ServerRow(Icons.Rounded.Person, "Artists", "Everyone on your server", null, onClick = nav.artists) }
                if (home.playlists.isNotEmpty()) {
                    item(key = "pl-header") { SectionHeader("Playlists") }
                    items(home.playlists, key = { "pl:${it.id}" }) { p ->
                        ServerRow(Icons.AutoMirrored.Rounded.QueueMusic, p.name, "${p.songCount} songs", p.cover) { nav.playlist(p.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun ServerAlbumScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit, onArtist: (String) -> Unit) {
    val loader = rememberLoader("server:album:$id") { Subsonic.album(id) }
    val state by loader.state.collectAsState()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is UiState.Loading -> item { PageHeader("", onBack = onBack) }
            is UiState.Error -> {
                item { PageHeader("", onBack = onBack) }
                item { ErrorState(s.message, onRetry = { loader.reload() }) }
            }
            is UiState.Success -> {
                val (album, songs) = s.data
                item { PageHeader(album.name, onBack = onBack) }
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(album.cover.artworkAt(600), Modifier.size(132.dp), MaterialTheme.shapes.large)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(
                                album.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(enabled = album.artistId != null) { album.artistId?.let(onArtist) },
                            )
                            Text(
                                listOfNotNull(album.year?.toString(), "${songs.size} songs").joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    PlayButtons(
                        onPlay = { actions.playAll(songs, 0, false, album.name) },
                        onShuffle = { actions.playAll(songs, 0, true, album.name) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                songRows(songs, actions, album.name)
            }
        }
    }
}

@Composable
fun ServerPlaylistScreen(id: String, contentPadding: PaddingValues, actions: SongActions, onBack: () -> Unit) {
    val loader = rememberLoader("server:playlist:$id") { Subsonic.playlist(id) }
    val state by loader.state.collectAsState()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is UiState.Loading -> item { PageHeader("", onBack = onBack) }
            is UiState.Error -> {
                item { PageHeader("", onBack = onBack) }
                item { ErrorState(s.message, onRetry = { loader.reload() }) }
            }
            is UiState.Success -> {
                val (playlist, songs) = s.data
                item { PageHeader(playlist.name, onBack = onBack) }
                item {
                    PlayButtons(
                        onPlay = { actions.playAll(songs, 0, false, playlist.name) },
                        onShuffle = { actions.playAll(songs, 0, true, playlist.name) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                songRows(songs, actions, playlist.name)
            }
        }
    }
}

@Composable
fun ServerArtistScreen(id: String, contentPadding: PaddingValues, nav: ServerNav, onBack: () -> Unit) {
    val loader = rememberLoader("server:artist:$id") { Subsonic.artist(id) }
    val state by loader.state.collectAsState()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is UiState.Loading -> item { PageHeader("", onBack = onBack) }
            is UiState.Error -> {
                item { PageHeader("", onBack = onBack) }
                item { ErrorState(s.message, onRetry = { loader.reload() }) }
            }
            is UiState.Success -> {
                val (artist, albums) = s.data
                item { PageHeader(artist.name, onBack = onBack) }
                items(albums, key = { it.id }) { a ->
                    ServerRow(null, a.name, listOfNotNull(a.year?.toString(), "${a.songCount} songs").joinToString(" · "), a.cover) { nav.album(a.id) }
                }
            }
        }
    }
}

@Composable
fun ServerArtistsScreen(contentPadding: PaddingValues, nav: ServerNav, onBack: () -> Unit) {
    val loader = rememberLoader("server:artists") { Subsonic.artists() }
    val state by loader.state.collectAsState()
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item { PageHeader("Artists", onBack = onBack) }
        when (val s = state) {
            is UiState.Loading -> items(3) { ShelfPlaceholder() }
            is UiState.Error -> item { ErrorState(s.message, onRetry = { loader.reload() }) }
            is UiState.Success -> items(s.data, key = { it.id }) { a ->
                ServerRow(Icons.Rounded.Person, a.name, "${a.albumCount} albums", a.cover, round = true) { nav.artist(a.id) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.songRows(songs: List<Song>, actions: SongActions, source: String) {
    itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
        SongListItem(
            song = song,
            onClick = { actions.playAll(songs, i, false, source) },
            isCurrent = song.videoId == actions.currentVideoId,
            isPlaying = actions.isPlaying,
            onPlayNext = { actions.playNext(song) },
            onAddToQueue = { actions.addToQueue(song) },
        )
    }
}

@Composable
private fun AlbumShelf(title: String, albums: List<Subsonic.Album>, nav: ServerNav) {
    Shelf(title = title, items = albums) { a ->
        ItemCard(a.name, a.artist, a.cover, BrowseType.ALBUM, onClick = { nav.album(a.id) })
    }
}

@Composable
private fun ArtistShelf(title: String, artists: List<Subsonic.Artist>, nav: ServerNav) {
    Shelf(title = title, items = artists) { a ->
        ItemCard(a.name, "${a.albumCount} albums", a.cover, BrowseType.ARTIST, onClick = { nav.artist(a.id) })
    }
}

@Composable
private fun ServerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String,
    cover: String?,
    round: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Artwork(
            cover.artworkAt(ROW_ART_PX),
            Modifier.size(52.dp),
            if (round) CircleShape else MaterialTheme.shapes.small,
            placeholder = icon ?: Icons.AutoMirrored.Rounded.QueueMusic,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
