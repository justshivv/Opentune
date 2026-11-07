package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What a search found, sorted into the rows the page shows. */
data class Found(val songs: List<Song>, val artists: List<BrowseItem>, val albums: List<BrowseItem>, val playlists: List<BrowseItem>) {
    val isEmpty get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()

    companion object {
        fun of(rows: List<SearchResult>): Found {
            val browse = rows.filterIsInstance<SearchResult.Browse>().map { it.item }
            return Found(
                songs = rows.mapNotNull { (it as? SearchResult.TopTrack)?.song ?: (it as? SearchResult.Track)?.song }.distinctBy { it.videoId },
                artists = browse.filter { it.type == BrowseType.ARTIST },
                albums = browse.filter { it.type == BrowseType.ALBUM },
                playlists = browse.filter { it.type == BrowseType.PLAYLIST },
            )
        }
    }
}

/** Search as you type: the TV's own keyboard opens on the field, and results follow a short pause. */
@Composable
fun SearchScreen(nav: TvNav) {
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Load<Found>>(Load.Done(Found(emptyList(), emptyList(), emptyList(), emptyList()))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(nav.pendingSearch) { nav.pendingSearch?.let { query = it; nav.pendingSearch = null } }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { found = Load.Done(Found(emptyList(), emptyList(), emptyList(), emptyList())); return@LaunchedEffect }
        delay(DEBOUNCE_MS)
        found = Load.Loading
        found = try { Load.Done(Found.of(MusicRepository.search(q, SearchFilter.ALL))) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "field") {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = TvSafe.Horizontal).focusRequester(focus),
                placeholder = { Text("Search songs, albums, artists") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
        }
        when (val f = found) {
            Load.Loading -> item { TvNote("Searching…") }
            Load.Failed -> item { TvNote("Couldn't search just now. Check the connection.") }
            is Load.Done -> {
                val r = f.value
                if (query.trim().length >= 2 && r.isEmpty) item { TvNote("Nothing found for “${query.trim()}”.") }
                if (r.songs.isNotEmpty()) item(key = "songs") {
                    TvShelf("Songs") {
                        items(r.songs, key = { it.videoId }) { song -> SongCard(song, { nav.play(song, "Search") }) }
                    }
                }
                if (r.artists.isNotEmpty()) item(key = "artists") {
                    TvShelf("Artists") {
                        items(r.artists, key = { it.browseId }) { a -> TvCard(a.title, null, a.thumbnailUrl, { nav.open(Dest.Artist(a.browseId, a.title)) }, round = true) }
                    }
                }
                if (r.albums.isNotEmpty()) item(key = "albums") {
                    TvShelf("Albums") {
                        items(r.albums, key = { it.browseId }) { a -> TvCard(a.title, a.subtitle, a.thumbnailUrl, { nav.open(Dest.Collection(a.browseId, a.title, a.thumbnailUrl)) }) }
                    }
                }
                if (r.playlists.isNotEmpty()) item(key = "playlists") {
                    TvShelf("Playlists") {
                        items(r.playlists, key = { it.browseId }) { a -> TvCard(a.title, a.subtitle, a.thumbnailUrl, { nav.open(Dest.Collection(a.browseId, a.title, a.thumbnailUrl)) }) }
                    }
                }
            }
        }
    }
}

private const val DEBOUNCE_MS = 450L

