package com.opentune.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    currentVideoId: String?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onBrowse: (BrowseItem) -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val filter by vm.filter.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val results by vm.results.collectAsState()
    val recent by AppSettings.recentSearches.collectAsState()
    val focus = LocalFocusManager.current

    fun submit(q: String) {
        focus.clearFocus()
        vm.submit(q)
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Filled.Close, "Clear") }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
        )

        when {
            submitted == null || submitted != query.trim() -> Typing(
                query = query,
                suggestions = suggestions,
                recent = recent,
                contentPadding = contentPadding,
                onPick = ::submit,
                onFill = vm::onQueryChange,
            )
            else -> Column {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SearchFilter.entries) { f ->
                        FilterChip(selected = f == filter, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
                    }
                }
                Results(results, contentPadding, currentVideoId, isPlaying, onPlay, onPlayNext, onAddToQueue, onBrowse, vm::retry)
            }
        }
    }
}

@Composable
private fun Typing(
    query: String,
    suggestions: List<String>,
    recent: List<String>,
    contentPadding: PaddingValues,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        if (query.isBlank()) {
            if (recent.isEmpty()) {
                item {
                    MessageState(
                        Icons.Filled.Search,
                        "Find something to play",
                        message = "Search for a song, artist, album or playlist.",
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = AppSettings::clearRecentSearches) { Text("Clear all") }
                    }
                }
                items(recent) { q ->
                    QueryRow(q, Icons.Filled.History, onClick = { onPick(q) }) {
                        IconButton(onClick = { AppSettings.removeRecentSearch(q) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
            }
        } else {
            items(suggestions) { s ->
                QueryRow(s, Icons.Filled.Search, onClick = { onPick(s) }) {
                    IconButton(onClick = { onFill(s) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Use this", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, Modifier.weight(1f).padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun Results(
    state: UiState<List<SearchResult>>,
    contentPadding: PaddingValues,
    currentVideoId: String?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onBrowse: (BrowseItem) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is UiState.Loading -> Column(Modifier.padding(top = 8.dp)) { repeat(8) { SongRowPlaceholder() } }
        is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
            ErrorState(state.message, onRetry)
        }
        is UiState.Success -> if (state.data.isEmpty()) {
            MessageState(Icons.Filled.SearchOff, "No results", Modifier.padding(top = 48.dp), message = "Try different words or another filter.")
        } else {
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(state.data, key = { r ->
                    when (r) {
                        is SearchResult.TopTrack -> "t:${r.song.videoId}"
                        is SearchResult.Track -> "v:${r.song.videoId}"
                        is SearchResult.Browse -> "b:${r.item.browseId}"
                    }
                }) { r ->
                    when (r) {
                        is SearchResult.TopTrack -> TopResult(r.song, onPlay = { onPlay(r.song) })
                        is SearchResult.Track -> SongListItem(
                            song = r.song,
                            onClick = { onPlay(r.song) },
                            isCurrent = r.song.videoId == currentVideoId,
                            isPlaying = isPlaying,
                            onPlayNext = { onPlayNext(r.song) },
                            onAddToQueue = { onAddToQueue(r.song) },
                        )
                        is SearchResult.Browse -> BrowseRow(r.item, onClick = { onBrowse(r.item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TopResult(song: Song, onPlay: () -> Unit) {
    Surface(
        onClick = onPlay,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.size(84.dp), MaterialTheme.shapes.medium)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Top result", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.PlayArrow, "Play") }
        }
    }
}

@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val round = item.type == BrowseType.ARTIST
        Artwork(
            item.thumbnailUrl.artworkAt(ROW_ART_PX),
            Modifier.size(52.dp),
            shape = if (round) CircleShape else MaterialTheme.shapes.small,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                when (item.type) {
                    BrowseType.ALBUM -> "Album"
                    BrowseType.ARTIST -> "Artist"
                    BrowseType.PLAYLIST -> "Playlist"
                    BrowseType.OTHER -> "Open"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
