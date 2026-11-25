package com.opentune.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.i18n.Tr
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

/** Where a search's results are kept in the page's memory, so Back to Search shows them at once. */
internal fun searchKey(query: String) = "search:" + query.trim().lowercase()

/**
 * Search as you type. The field is at the top; opened fresh, it starts typing with the TV's own
 * keyboard, and results follow a short pause, as rows of songs, artists, albums and playlists.
 * Before anything is typed, the recent searches (OK runs one again) or a hint. Down from the
 * field reaches the results; Back from a result's page lands on the card that was opened.
 */
@Composable
fun SearchScreen(nav: TvNav) {
    val memory = LocalPageMemory.current
    // A spoken search ("play … on NepTune") arrives typed in, without the keyboard.
    var query by rememberSaveable { mutableStateOf(nav.pendingSearch.orEmpty()) }
    val q = query.trim()
    var attempt by remember { mutableIntStateOf(0) }
    @Suppress("UNCHECKED_CAST")
    var found by remember { mutableStateOf(memory?.loads?.get(searchKey(q)) as? Load<Found>) }
    val recent by AppSettings.recentSearches.collectAsState()

    LaunchedEffect(nav.pendingSearch) { nav.pendingSearch?.let { query = it; nav.pendingSearch = null } }
    LaunchedEffect(q, attempt) {
        if (q.length < 2) { found = null; return@LaunchedEffect }
        val kept = memory?.loads?.get(searchKey(q))
        if (kept is Load.Done<*> && attempt == 0) {
            @Suppress("UNCHECKED_CAST")
            found = kept as Load<Found>
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        found = Load.Loading
        found = try {
            Load.Done(Found.of(MusicRepository.search(q, SearchFilter.ALL))).also { done ->
                memory?.loads?.let { loads ->
                    loads.keys.removeAll { it is String && it.startsWith("search:") }
                    loads[searchKey(q)] = done
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }
    // What was searched for is kept once the viewer acts on it: pressed Search, or opened a result.
    val keep = { if (q.length >= 2) AppSettings.addRecentSearch(q) }
    // When what the focus is on goes away (a recent search picked, Try again), it goes to the field.
    val field = remember { FocusRequester() }
    val toField = { runCatching { field.requestFocus() }; Unit }
    // Down from the wide field goes to the first item under it, not the one under its middle.
    val f = found
    val firstKey = when {
        q.length < 2 || f == null -> recent.firstOrNull()?.let { "recent/0:$it" }
        f is Load.Done -> f.value.let { r ->
            r.songs.firstOrNull()?.let { "songs/0:${it.videoId}" }
                ?: r.artists.firstOrNull()?.let { "artists/0:${it.browseId}" }
                ?: r.albums.firstOrNull()?.let { "Albums/0:${it.browseId}" }
                ?: r.playlists.firstOrNull()?.let { "Playlists/0:${it.browseId}" }
        }
        else -> null
    }

    TvPage(scroll = SearchScroll) {
        item(key = "field") {
            TvTextField(
                value = query, onValueChange = { query = it },
                placeholder = "Search songs, albums, artists",
                modifier = Modifier.safeSides().focusProperties {
                    firstKey?.let { key -> memory?.requesters?.get(key) }?.let { down = it }
                },
                onSubmit = keep,
                focusKey = "field", entryFocus = true, focusRequester = field,
                // Opened fresh with nothing typed: straight to the keyboard. Not when coming back.
                editNow = query.isBlank() && memory?.focusKey == null,
            )
        }
        when {
            q.length < 2 || f == null -> if (recent.isNotEmpty()) item(key = "recent") {
                RecentSearches(
                    recent,
                    onPick = { query = it; AppSettings.addRecentSearch(it); toField() },
                    onClear = { toField(); AppSettings.clearRecentSearches() },
                )
            } else item(key = "hint") {
                TvEmptyState("Find something to play", message = "Search for a song, artist, album or playlist.", icon = Icons.Rounded.Search)
            }
            f is Load.Loading -> item(key = "loading") { TvLoadingState(label = "Searching…") }
            f is Load.Failed -> item(key = "failed") {
                TvErrorState({ toField(); attempt++ }, message = "Couldn't search just now. Check the connection.")
            }
            f is Load.Done -> {
                val r = f.value
                if (r.isEmpty) item(key = "none") {
                    TvEmptyState(Tr.f("Nothing found for \"{0}\"", q), icon = Icons.Rounded.SearchOff)
                }
                if (r.songs.isNotEmpty()) item(key = "songs") {
                    TvShelf("Songs") {
                        itemsIndexed(r.songs, key = { _, s -> s.videoId }) { i, song ->
                            SongCard(song, { keep(); nav.play(song, "Search") }, focusKey = "songs/$i:${song.videoId}")
                        }
                    }
                }
                if (r.artists.isNotEmpty()) item(key = "artists") {
                    TvShelf("Artists") {
                        itemsIndexed(r.artists, key = { i, a -> "$i:${a.browseId}" }) { i, a ->
                            TvCard(
                                a.title, null, a.thumbnailUrl, { keep(); nav.open(Dest.Artist(a.browseId, a.title)) },
                                kind = TvCardKind.Round, focusKey = "artists/$i:${a.browseId}",
                            )
                        }
                    }
                }
                listOf("Albums" to r.albums, "Playlists" to r.playlists).forEach { (title, list) ->
                    if (list.isNotEmpty()) item(key = title) {
                        TvShelf(title) {
                            itemsIndexed(list, key = { i, a -> "$i:${a.browseId}" }) { i, a ->
                                TvCard(
                                    a.title, a.subtitle, a.thumbnailUrl,
                                    { keep(); nav.open(Dest.Collection(a.browseId, a.title, a.thumbnailUrl)) },
                                    focusKey = "$title/$i:${a.browseId}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The searches made lately, as a row of pills (OK runs one again), and Clear at its end. */
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Recent searches", style = TvType.Title, modifier = Modifier.safeSides())
        LazyRow(
            contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(TvSpace.M),
            modifier = Modifier.fillMaxWidth().focusRestorer(),
        ) {
            itemsIndexed(recent, key = { _, s -> s }) { i, s ->
                TvPillButton(s, { onPick(s) }, icon = Icons.Rounded.History, focusKey = "recent/$i:$s")
            }
            item(key = "tv:clear") { TvPillButton("Clear", onClear, icon = Icons.Rounded.Close, focusKey = "recent/clear") }
        }
    }
}

/**
 * Where Search keeps the focused card: low enough that the first row of results doesn't push the
 * field up under the bar, high enough that a later row's card and its two lines show whole.
 */
private val SearchScroll = 0.16f..0.38f

private const val DEBOUNCE_MS = 450L
