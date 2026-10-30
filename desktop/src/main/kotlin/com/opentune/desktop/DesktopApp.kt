package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.opentune.data.model.ShelfItem
import com.opentune.data.history.DailyMixes
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import com.opentune.ui.PageMotion
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The sidebar's pages; an opened album, playlist or artist goes over them. */
internal enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search, Icons.Rounded.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    QUEUE("Up next", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** A playlist made here, opened from the sidebar or Library. */
internal data class PlaylistKey(val id: String)

/** What fills the page area: a tab, or something opened over it. */
private data class Page(val tab: Tab, val shelf: Shelf? = null, val opened: Any? = null)

/**
 * The window, laid out as a Mac music app is: a sidebar with search, the
 * pages and your playlists; a bar along the top with the transport, the song
 * playing and volume; the page below it, with lyrics or the queue in a
 * column beside it when asked ([startPanel] opens with one, for pictures).
 * Clicking the song opens the full player.
 */
@Composable
fun DesktopApp(startPanel: SidePanel? = null) {
    var page by remember { mutableStateOf(Page(Tab.HOME)) }
    // Back goes through what was opened, the way it came.
    val history = remember { mutableStateListOf<Page>() }
    var query by remember { mutableStateOf("") }
    var searches by remember { mutableStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(startPanel) }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val song = queue.getOrNull(index)
    val ui by AppSettings.ui.collectAsState()
    fun go(to: Page) {
        if (to == page) return
        history.add(page)
        if (history.size > 30) history.removeAt(0)
        page = to
    }
    val back: () -> Unit = { history.removeLastOrNull()?.let { page = it } }

    DesktopTheme(artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 900.dp
                val roomForPanel = maxWidth >= 1040.dp
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        compact = compact,
                        page = page.tab.takeIf { page.opened == null || page.opened is PlaylistKey },
                        shelf = page.shelf.takeIf { page.tab == Tab.LIBRARY && page.opened == null },
                        openPlaylist = (page.opened as? PlaylistKey)?.id,
                        query = query,
                        onQuery = { query = it },
                        onSearch = { searches++; go(Page(Tab.SEARCH)) },
                        onTab = { go(Page(it)) },
                        onShelf = { go(Page(Tab.LIBRARY, it)) },
                        onPlaylist = { go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, PlaylistKey(it))) },
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(
                            song = song,
                            canGoBack = history.isNotEmpty(),
                            onBack = back,
                            panel = panel,
                            onPanel = { p -> panel = if (panel == p) null else p },
                            onOpenPlayer = { playerOpen = true },
                        )
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                PageBackdrop(song?.thumbnailUrl)
                                CompositionLocalProvider(LocalPageBottom provides 32.dp) {
                                    Pages(
                                        page = page,
                                        still = ui.reduceAnimation,
                                        queue = queue,
                                        index = index,
                                        compact = compact,
                                        query = query,
                                        onQuery = { query = it },
                                        searches = searches,
                                        onSearch = { searches++ },
                                        onOpen = { go(page.copy(opened = it)) },
                                        onOpenAny = { go(page.copy(opened = it)) },
                                        onShelf = { go(Page(Tab.LIBRARY, it)) },
                                        onPlaylist = { go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, PlaylistKey(it))) },
                                        onBack = { back() },
                                    )
                                }
                            }
                            val shown = rememberLast(panel)
                            AnimatedVisibility(
                                visible = panel != null && song != null && roomForPanel,
                                enter = if (ui.reduceAnimation) fadeIn(tween(140)) else expandHorizontally(Motion.soft(), Alignment.Start) + fadeIn(tween(220, 80)),
                                exit = if (ui.reduceAnimation) fadeOut(tween(120)) else shrinkHorizontally(tween(260), Alignment.Start) + fadeOut(tween(160)),
                            ) {
                                if (song != null && shown != null) SidePanelColumn(shown, song, queue, index) { panel = it }
                            }
                        }
                    }
                }

                // The full player rises over everything.
                val full = rememberLast(song)
                AnimatedVisibility(
                    visible = playerOpen && song != null,
                    enter = PageMotion.playerIn(ui.playerMotion, ui.reduceAnimation),
                    exit = PageMotion.playerOut(ui.playerMotion, ui.reduceAnimation),
                ) { full?.let { NowPlaying(it, docked = false, onClose = { playerOpen = false }) } }
            }
        }
    }
}

/**
 * The open page: the new one fades in with a short rise (a short drop going
 * back), the old one fades out.
 */
@Composable
private fun Pages(
    page: Page,
    still: Boolean,
    queue: List<Song>,
    index: Int,
    compact: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    searches: Int,
    onSearch: () -> Unit,
    onOpen: (ShelfItem) -> Unit,
    onOpenAny: (Any) -> Unit,
    onShelf: (Shelf) -> Unit,
    onPlaylist: (String) -> Unit,
    onBack: () -> Unit,
) {
    val pxPerDp = androidx.compose.ui.platform.LocalDensity.current.density
    AnimatedContent(
        page,
        transitionSpec = {
            // A desktop page changes with a quick fade and a short rise, not the phone's
            // full-page slides and scales: moving a whole window of covers each frame stutters.
            val back = initialState.opened != null && targetState.opened == null
            val rise = (if (still) 0 else if (back) -12 else 16) * pxPerDp
            (fadeIn(tween(if (still) 120 else 200, delayMillis = if (still) 0 else 40)) +
                slideInVertically(tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { rise.toInt() }) togetherWith
                fadeOut(tween(if (still) 90 else 110))
        },
        label = "page",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val opened = shown.opened
        when {
            opened is ShelfItem -> CollectionPage(opened)
            opened is PlaylistKey -> PlaylistPage(opened.id, onBack = onBack)
            opened is MoodKey -> MoodPage(opened, onOpen)
            opened is DailyMixes.Mix -> MixPage(opened)
            shown.tab == Tab.SEARCH -> SearchPage(query, onQuery, searches, onSearch, showField = compact)
            shown.tab == Tab.HOME -> HomePage(onOpen = onOpen, onOpenAny = onOpenAny)
            shown.tab == Tab.LIBRARY -> shown.shelf?.let { LibraryPage(it, onShelf, onPlaylist) }
                ?: LibraryHome(onShelf = onShelf, onPlaylist = onPlaylist, onOpenAny = onOpenAny)
            shown.tab == Tab.SETTINGS -> SettingsPage()
            else -> SongPage("Up next", queue, "The queue is empty.", current = index, onPlay = { _, i -> Player.playAt(i) })
        }
    }
}

/** The last non-null [value], so something leaving can be drawn as it goes. */
@Composable
internal fun <T> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** Room under each page's last row, clear of whatever floats over the bottom of the window. */
internal val LocalPageBottom = staticCompositionLocalOf { 32.dp }

// ---- Pages -------------------------------------------------------------------

/**
 * Search: the sidebar's box is its field, and Enter there runs it ([searches]
 * counts the runs). [showField] puts a field on the page itself, for a
 * window too narrow for the sidebar's box.
 */
@Composable
private fun SearchPage(query: String, onQuery: (String) -> Unit, searches: Int, onSearch: () -> Unit, showField: Boolean) {
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var shownFor by remember { mutableStateOf("") }
    val recentSearches by AppSettings.recentSearches.collectAsState()
    LaunchedEffect(searches) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        AppSettings.addRecentSearch(q)
        searching = true
        failed = false
        runCatching { Pages.search(q) }.onSuccess { results = it; shownFor = q }.onFailure { failed = true }
        searching = false
    }
    // Cleared: back to recent searches.
    LaunchedEffect(query) { if (query.isBlank()) { results = null; shownFor = "" } }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (showField) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle(if (shownFor.isEmpty()) "Search" else "Results for “$shownFor”")
        if (showField) {
            TextField(
                query, onQuery,
                placeholder = { Text("Songs, albums, artists") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(focus),
            )
        }
        val found = results
        when {
            searching -> Loading()
            failed -> Message("Couldn't search", "Check your connection and try again.")
            found == null && recentSearches.isNotEmpty() -> RecentSearches(recentSearches) { onQuery(it); onSearch() }
            found == null -> Message("Find something to play", "Type in the search box and press Enter.", icon = Icons.Rounded.Search)
            found.isEmpty() -> Message("No results", "Try different words.", icon = Icons.Rounded.SearchOff)
            else -> SongList(found) { songs, i -> Player.playAll(songs, i) }
        }
    }
}

/** What you searched for lately, as the phone lists it under the search box. */
@Composable
private fun RecentSearches(queries: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = AppSettings::clearRecentSearches) { Text("Clear") }
        }
        queries.forEach { q ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(q) }.padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
                IconButton(onClick = { AppSettings.removeRecentSearch(q) }) {
                    Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Liked songs, your playlists, your server and what you played lately; [shelf] is the one showing. */
@Composable
private fun LibraryPage(shelf: Shelf, onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit) {
    val liked by Library.liked.collectAsState()
    val played by Library.recent.collectAsState()
    val server by Subsonic.server.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle("Library")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Shelf.entries.filter { it != Shelf.SERVER || server != null }.forEach { s -> Segment(s.label, shelf == s) { onShelf(s) } }
        }
        AnimatedContent(
            shelf,
            transitionSpec = { (fadeIn(Motion.fade(240)) + slideInHorizontally(Motion.soft()) { if (targetState.ordinal > initialState.ordinal) 40 else -40 }) togetherWith fadeOut(Motion.fade(110)) },
            label = "library shelf",
            modifier = Modifier.fillMaxSize(),
        ) { shelf ->
        when (shelf) {
            Shelf.PLAYLISTS -> PlaylistGrid(onOpen = onPlaylist)
            Shelf.LIKED -> if (liked.isEmpty()) Message("No liked songs yet", "Click the heart on a song to keep it here.")
                else SongList(liked) { s, i -> Player.playAll(s, i) }
            Shelf.SERVER -> if (server != null) ServerShelf() else Message("No music server connected", "Connect one in Settings.")
            Shelf.RECENT -> if (played.isEmpty()) Message("Nothing played yet", "Songs you play show here.")
                else SongList(played) { s, i -> Player.playAll(s, i) }
        }
        }
    }
}

internal enum class Shelf(val label: String) { LIKED("Liked songs"), PLAYLISTS("Playlists"), SERVER("Your server"), RECENT("Recently played") }

@Composable
private fun SongPage(title: String, songs: List<Song>, empty: String, current: Int = -1, onPlay: (List<Song>, Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle(title)
        if (songs.isEmpty()) Message(empty, null) else SongList(songs, current, onPlay = onPlay)
    }
}

/** The songs of an album, playlist, artist or podcast, with a way back. */
@Composable
private fun CollectionPage(item: ShelfItem) {
    var songs by remember(item) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        runCatching { Pages.songsOf(item.browseId!!) }.onSuccess { songs = it }.onFailure { failed = true }
    }
    val round = item.browseId?.startsWith("UC") == true
    val found = songs
    val bg = MaterialTheme.colorScheme.background
    val colour = rememberCoverColor(item.thumbnailUrl, bg)
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint(colour, bg, 0.34f), 0.55f to tint(colour, bg, 0.16f), 1f to tint(colour, bg, 0.08f)))
            .padding(top = 24.dp),
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = if (round) CircleShape else MaterialTheme.shapes.medium
            Cover(item.thumbnailUrl, 168.dp, shape, Modifier.shadow(24.dp, shape, ambientColor = colour, spotColor = colour))
            Column(Modifier.padding(start = 20.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.animation.AnimatedVisibility(!found.isNullOrEmpty(), enter = fadeIn(Motion.fade()) + scaleIn(Motion.soft(), 0.85f)) {
                    val press = remember { MutableInteractionSource() }
                    Row(
                        Modifier.padding(top = 12.dp).hoverLift(press, 1.05f).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .clickable(interactionSource = press, indication = null) { found?.let { Player.playAll(it, 0) } }
                            .padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Text("Play", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        when {
            failed -> Message("Couldn't open ${item.title}", "Check your connection and try again.")
            found == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Placeholder(Modifier.size(52.dp), MaterialTheme.shapes.small)
                        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Placeholder(Modifier.width(220.dp).height(14.dp), RoundedCornerShape(6.dp))
                            Placeholder(Modifier.width(140.dp).height(11.dp), RoundedCornerShape(5.dp))
                        }
                    }
                }
            }
            found.isEmpty() -> Message("Nothing to play here", null)
            else -> SongList(found) { s, i -> Player.playAll(s, i) }
        }
    }
}

// ---- Rows and cards ----------------------------------------------------------

/** Songs, one to a row: play on click, add to a playlist, like, and [onRemove] where a list can lose them. */
@Composable
internal fun SongList(songs: List<Song>, current: Int = -1, onRemove: ((Song) -> Unit)? = null, onPlay: (List<Song>, Int) -> Unit) {
    val liked by Library.liked.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playingId = queue.getOrNull(index)?.videoId
    val playingNow by Player.playing.collectAsState()
    // Rows rise in only as the list first shows; scrolled back into view, they're simply there.
    val since = remember(songs) { System.nanoTime() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalPageBottom.current)) {
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isLiked = liked.any { it.videoId == song.videoId }
            val isCurrent = if (current >= 0) i == current else song.videoId == playingId
            var adding by remember { mutableStateOf(false) }
            val hover = remember { MutableInteractionSource() }
            Row(
                Modifier.appear(i, since).padding(horizontal = 8.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = hover, indication = null) { onPlay(songs, i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Cover(song.thumbnailUrl, 52.dp, MaterialTheme.shapes.small)
                    androidx.compose.animation.AnimatedVisibility(isCurrent, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                        Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            PlayingBars(playingNow)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AddToPlaylistMenu(song, adding) { adding = false }
                }
                LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, size = 24.dp)
                if (onRemove != null) {
                    IconButton(onClick = { onRemove(song) }) {
                        Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Card(item: ShelfItem, onClick: () -> Unit) {
    val round = item.browseId?.startsWith("UC") == true
    val align = if (round) TextAlign.Center else TextAlign.Start
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        Modifier.width(160.dp).hoverLift(hover).clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = hover, indication = null, onClick = onClick).padding(bottom = 4.dp),
    ) {
        Box {
            Cover(item.thumbnailUrl, 160.dp, if (round) CircleShape else MaterialTheme.shapes.medium)
            androidx.compose.animation.AnimatedVisibility(
                hovered, enter = fadeIn(Motion.fade(160)) + scaleIn(Motion.soft(), 0.7f), exit = fadeOut(Motion.fade(120)) + scaleOut(Motion.soft(), 0.7f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            ) {
                Box(Modifier.size(42.dp).shadow(10.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun Cover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    AsyncImage(
        // A song on your own server carries a reference to its cover, without the credentials.
        model = remember(url) { ImageRequest.Builder(context).data(Subsonic.resolveCover(url)).crossfade(260).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** A page's big bold name, as the phone's pages start. */
@Composable
internal fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

/** A round glass button holding one icon, as the phone's pages put back, menu and settings. */
@Composable
internal fun GlassCircle(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier.size(size).pressable(onClick).glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size * 0.46f), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun Message(title: String, message: String?, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.appear(0).fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon?.let { Icon(it, null, Modifier.size(40.dp).padding(bottom = 8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
    }
}

/** Minutes and seconds, as the bar shows them. */
internal object Time {
    fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
