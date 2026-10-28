package com.opentune.ui.search

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.filled.GraphicEq
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
import com.opentune.ui.components.menuAnchor
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.SongListItem
import com.opentune.ui.components.SongRowPlaceholder

/** Asks the search page to put the cursor in its bar: the search button tapped while it's open. */
object SearchFocus {
    internal val requests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun request() { requests.tryEmit(Unit) }
}

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    currentVideoId: String?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onBrowse: (BrowseItem) -> Unit,
    /** Opens the page that names a song from what the microphone hears. */
    onRecognize: (() -> Unit)? = null,
    /** Opens a playlist or saved album the library search found. */
    onOpenList: (LibraryList) -> Unit = {},
    /**
     * Explore, when it has no tab of its own: it fills the page until the bar
     * is tapped or holds a search, and the bar's back arrow brings it back.
     */
    explore: (@Composable () -> Unit)? = null,
    vm: SearchViewModel = viewModel(),
) {
    val query by vm.query.collectAsState()
    val submitted by vm.submitted.collectAsState()
    val filter by vm.filter.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val live by vm.live.collectAsState()
    val results by vm.results.collectAsState()
    val recent by AppSettings.recentSearches.collectAsState()
    val recentAddon by AppSettings.recentAddonSearches.collectAsState()
    val source by vm.source.collectAsState()
    val library by vm.library.collectAsState()
    val libraryLists by vm.libraryLists.collectAsState()
    val addons by vm.addons.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // The search button tapped again while here: the cursor goes into the bar, keyboard up.
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        SearchFocus.requests.collect {
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
    val searching = explore == null || focused || query.isNotEmpty()

    fun submit(q: String) {
        focus.clearFocus()
        if (source == SearchSource.YOUTUBE) vm.submit(q) else { vm.onQueryChange(q); vm.remember(q) }
    }

    // An add-on's track plays as the same song from YouTube Music, started on the add-on's stream.
    val playHit: (AddonHit) -> Unit = { hit ->
        focus.clearFocus()
        vm.remember(query)
        scope.launch {
            val song = vm.songFor(hit)
            if (song != null) onPlay(song) else com.opentune.data.Pills.error("Couldn't find \"${hit.track.title}\" to play")
        }
    }

    // Back to Explore: the bar lets go and empties.
    val leave = {
        focus.clearFocus()
        vm.onQueryChange("")
    }
    androidx.activity.compose.BackHandler(enabled = explore != null && searching, onBack = leave)

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(field)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text(source.placeholder) },
            leadingIcon = {
                SearchLeadingIcon(explore != null && searching, onBack = leave,
                    onSearch = { field.requestFocus(); keyboard?.show() })
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Filled.Close, "Clear") }
                } else if (onRecognize != null) {
                    IconButton(onClick = onRecognize) { Icon(Icons.Filled.GraphicEq, "Name a song playing nearby") }
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
        // Where to look, while the bar is in use.
        androidx.compose.animation.AnimatedVisibility(
            focused,
            enter = androidx.compose.animation.fadeIn(tween(180)) + androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 600f)),
            exit = androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 600f)),
        ) {
            SourceBar(source, vm::setSource, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }

        when {
            !searching -> explore?.invoke()
            source == SearchSource.LIBRARY -> LibraryResults(query, library, libraryLists, contentPadding, currentVideoId, isPlaying, { focus.clearFocus(); onPlay(it) }, onPlayNext, onAddToQueue, { focus.clearFocus(); onOpenList(it) })
            source == SearchSource.ADDONS -> AddonResults(
                query, addons, recentAddon, contentPadding,
                onPick = { vm.onQueryChange(it) },
                onPlay = playHit,
            )
            submitted == null || submitted != query.trim() -> Typing(
                query = query,
                suggestions = suggestions,
                recent = recent,
                contentPadding = contentPadding,
                onPick = ::submit,
                onFill = vm::onQueryChange,
                live = live,
                currentVideoId = currentVideoId,
                isPlaying = isPlaying,
                onPlay = { focus.clearFocus(); onPlay(it) },
                onPlayNext = onPlayNext,
                onAddToQueue = onAddToQueue,
                onBrowse = { focus.clearFocus(); onBrowse(it) },
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

/** Both states occupy the same slot; focus changes only the glyph, never the field's layout. */
@Composable
internal fun SearchLeadingIcon(back: Boolean, onBack: () -> Unit, onSearch: () -> Unit) {
    IconButton(onClick = if (back) onBack else onSearch, modifier = Modifier.size(48.dp)) {
        androidx.compose.animation.Crossfade(back, modifier = Modifier.size(24.dp), animationSpec = tween(160), label = "searchLead") { showBack ->
            Icon(if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Search,
                if (showBack) "Back to Explore" else "Search", modifier = Modifier.fillMaxSize())
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
    live: List<SearchResult> = emptyList(),
    currentVideoId: String? = null,
    isPlaying: Boolean = false,
    onPlay: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToQueue: (Song) -> Unit = {},
    onBrowse: (BrowseItem) -> Unit = {},
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
            // A few ways to finish the words, then what they already find.
            val shownSuggestions = if (live.isEmpty()) suggestions else suggestions.take(LIVE_SUGGESTIONS)
            items(shownSuggestions, key = { "s:$it" }) { s ->
                QueryRow(s, Icons.Filled.Search, onClick = { onPick(s) }) {
                    IconButton(onClick = { onFill(s) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Use this", modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (live.isNotEmpty()) {
                item(key = "live-divider") {
                    androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        thickness = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                items(live, key = { r ->
                    when (r) {
                        is SearchResult.TopTrack -> "lt:${r.song.videoId}"
                        is SearchResult.Track -> "lv:${r.song.videoId}"
                        is SearchResult.Browse -> "lb:${r.item.browseId}"
                    }
                }) { r ->
                    Box(Modifier.animateItem()) {
                        when (r) {
                            // As plain rows here: the big card is for the full results.
                            is SearchResult.TopTrack -> SongListItem(
                                song = r.song,
                                onClick = { onPlay(r.song) },
                                isCurrent = r.song.videoId == currentVideoId,
                                isPlaying = isPlaying,
                                onPlayNext = { onPlayNext(r.song) },
                                onAddToQueue = { onAddToQueue(r.song) },
                            )
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
            // The mixed page comes back as one list; show it in sections the way
            // YouTube Music does: songs, then artists, albums, playlists.
            val sections = remember(state.data) { state.data.groupBy(::sectionOf).toList() }
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                sections.forEach { (title, rows) ->
                    if (sections.size > 1) {
                        item(key = "h:$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.animateItem().padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(rows, key = { r ->
                        when (r) {
                            is SearchResult.TopTrack -> "t:${r.song.videoId}"
                            is SearchResult.Track -> "v:${r.song.videoId}"
                            is SearchResult.Browse -> "b:${r.item.browseId}"
                        }
                    }) { r ->
                        Box(Modifier.animateItem()) {
                            when (r) {
                                is SearchResult.TopTrack -> TopResult(r.song, onPlay = { onPlay(r.song) }, isCurrent = r.song.videoId == currentVideoId)
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
    }
}

private fun sectionOf(r: SearchResult): String = when (r) {
    is SearchResult.TopTrack -> "Top result"
    is SearchResult.Track -> if (r.song.isVideo) "Videos" else "Songs"
    is SearchResult.Browse -> when (r.item.type) {
        BrowseType.ARTIST -> "Artists"
        BrowseType.ALBUM -> "Albums"
        BrowseType.PLAYLIST -> "Playlists"
        else -> "More"
    }
}

@Composable
private fun TopResult(song: Song, onPlay: () -> Unit, isCurrent: Boolean = false) {
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
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // The song that's playing reads in the accent, as it does in the rows.
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                )
                com.opentune.ui.components.SubtitleLine(song.artist, explicit = song.isExplicit == true, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.PlayArrow, "Play") }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit) {
    // Albums and playlists open their long-press menu, lifted out where the row is.
    val list = if (item.type == BrowseType.ALBUM || item.type == BrowseType.PLAYLIST)
        com.opentune.ui.components.MenuList(item.browseId, item.title, item.subtitle, item.thumbnailUrl, item.type) else null
    val anchor = remember { com.opentune.ui.components.MenuAnchor() }
    val view = androidx.compose.ui.platform.LocalView.current
    val haptics = com.opentune.ui.components.rememberHaptics()
    var menuOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
    val at = anchor.rect
    val round = item.type == BrowseType.ARTIST
    if (menuOpen && list != null && at != null) {
        com.opentune.ui.components.CollectionContextMenu(list, at, onDismiss = { menuOpen = false }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(52.dp), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    com.opentune.ui.components.SubtitleLine(item.subtitle, explicit = item.explicit)
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (list != null) Modifier.menuAnchor(anchor, view) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = list?.let { { haptics.press(); menuOpen = true } })
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            item.thumbnailUrl.artworkAt(ROW_ART_PX),
            Modifier.size(52.dp),
            shape = if (round) CircleShape else MaterialTheme.shapes.small,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            com.opentune.ui.components.SubtitleLine(item.subtitle, explicit = item.explicit)
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


/** How many suggestions stay above the live results. */
private const val LIVE_SUGGESTIONS = 3

/** YouTube, Library or Add-ons: one rounded track, the chosen one lit on a pill that slides to it. */
@Composable
internal fun SourceBar(selected: SearchSource, onSelect: (SearchSource) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(4.dp),
    ) {
        val segment = maxWidth / SearchSource.entries.size
        val at by androidx.compose.animation.core.animateDpAsState(segment * selected.ordinal, spring(dampingRatio = 0.85f, stiffness = 500f), label = "sourcePill")
        // Moved in the layout step: read here, it redrew the whole bar every frame of the slide.
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(at.roundToPx(), 0) }.width(segment).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(colors.onSurface))
        Row(Modifier.fillMaxSize()) {
            SearchSource.entries.forEach { s ->
                val on = s == selected
                val text by androidx.compose.animation.animateColorAsState(if (on) colors.surface else colors.onSurfaceVariant, label = "sourceText")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        // Not focusable, so tapping a tab leaves the keyboard and the bar as they are.
                        .then(if (com.opentune.ui.tv.LocalIsTv.current) Modifier.clickable { onSelect(s) }
                            else Modifier.pointerInput(s) { detectTapGestures { onSelect(s) } })
                        .semantics { role = Role.Tab; this.selected = on; contentDescription = s.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = text)
                }
            }
        }
    }
}

@Composable
private fun SourceHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
    )
}

/**
 * What in your library matches: playlists and saved albums by name first,
 * then songs that are liked, in your playlists, downloaded, or in the Local
 * Music folder.
 */
@Composable
private fun LibraryResults(
    query: String,
    found: List<Song>?,
    lists: List<LibraryList>,
    contentPadding: PaddingValues,
    currentVideoId: String?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenList: (LibraryList) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        if (query.isNotBlank() && found != null && lists.isNotEmpty()) {
            item(key = "lib:lists") { LibraryHeading("Playlists") }
            items(lists, key = { "lib:${it.key}" }) { list -> LibraryListRow(list) { onOpenList(list) } }
            if (found.isNotEmpty()) item(key = "lib:songs") { LibraryHeading("Songs") }
        }
        when {
            query.isBlank() || found == null -> item { SourceHint("Search your playlists, liked songs, downloads and the songs in your Local Music folder.") }
            found.isEmpty() && lists.isEmpty() -> item { SourceHint("Nothing in your library matches \"${query.trim()}\".") }
            else -> items(found, key = { "lib:${it.videoId}" }) { song ->
                SongListItem(
                    song = song,
                    onClick = { onPlay(song) },
                    isCurrent = song.videoId == currentVideoId,
                    isPlaying = isPlaying,
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                )
            }
        }
    }
}

@Composable
private fun LibraryHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun LibraryListRow(list: LibraryList, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (list.coverUrl != null || list.songCovers.isEmpty()) {
            com.opentune.ui.components.Artwork(list.coverUrl, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        } else {
            com.opentune.ui.components.PlaylistCover(list.title, list.songCovers, Modifier.size(56.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(list.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(list.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What your add-ons found, each with its own cover, quality and length; and the searches made here before. */
@Composable
private fun AddonResults(
    query: String,
    found: com.opentune.data.model.UiState<List<AddonHit>>?,
    recent: List<String>,
    contentPadding: PaddingValues,
    onPick: (String) -> Unit,
    onPlay: (AddonHit) -> Unit,
) {
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        if (query.isBlank() || found == null) {
            if (recent.isEmpty()) {
                item { SourceHint("Search every enabled add-on at once. The song you pick starts on that add-on's stream.") }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = AppSettings::clearRecentAddonSearches) { Text("Clear all") }
                    }
                }
                items(recent, key = { "ar:$it" }) { q ->
                    QueryRow(q, Icons.Filled.History, onClick = { onPick(q) }) {
                        IconButton(onClick = { AppSettings.removeRecentAddonSearch(q) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
            }
            return@LazyColumn
        }
        when (found) {
            is com.opentune.data.model.UiState.Loading -> items(6, key = { "ap$it" }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.opentune.ui.components.Placeholder(Modifier.size(56.dp), RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(14.dp))
                    com.opentune.ui.components.Placeholder(Modifier.weight(1f).height(18.dp), RoundedCornerShape(6.dp))
                }
            }
            is com.opentune.data.model.UiState.Error -> item { SourceHint(found.message) }
            is com.opentune.data.model.UiState.Success ->
                if (found.data.isEmpty()) item { SourceHint("None of your add-ons has \"${query.trim()}\".") }
                else items(found.data, key = { "ah:${it.addon.key}:${it.track.id}" }) { hit -> AddonRow(hit, onClick = { onPlay(hit) }) }
        }
    }
}

@Composable
internal fun AddonRow(hit: AddonHit, onClick: () -> Unit) {
    val t = hit.track
    val colors = MaterialTheme.colorScheme
    val codec = t.format.lowercase()
    val quality = if (codec.isBlank() || codec in setOf("flac", "alac", "wav", "pcm")) "Lossless" else codec.uppercase()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.opentune.ui.components.Artwork(t.artwork, Modifier.size(56.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                Text(
                    listOf(quality, t.artist.ifBlank { hit.manifest.name }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (t.durationMs > 0) {
            val secs = t.durationMs / 1000
            Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
    }
}
