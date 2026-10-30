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
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.focusRequester
import com.opentune.ui.BottomChrome
import com.opentune.ui.CHROME_MINI_HEIGHT
import com.opentune.ui.CHROME_TAB_HEIGHT
import com.opentune.ui.ChromeTab
import com.opentune.ui.LocalChromeScroll
import com.opentune.ui.NavChrome
import com.opentune.ui.NavRail
import com.opentune.ui.NowPlaying as Docking
import com.opentune.ui.NowPlayingStrip
import com.opentune.ui.PANE_GUTTER
import com.opentune.ui.PageMotion
import com.opentune.ui.PlayerPaneFrame
import com.opentune.ui.RAIL_GUTTER
import com.opentune.ui.STRIP_GUTTER
import com.opentune.ui.appLayout
import com.opentune.ui.components.LocalHazeState
import com.opentune.ui.components.glass
import com.opentune.ui.components.pressable
import com.opentune.ui.rememberChromeScroll
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pages, in the dock or the rail as on the phone; search sits apart in its own circle. */
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    QUEUE("Up next", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private val TABS = Tab.entries.map { ChromeTab(it.label, it.icon, it.selectedIcon) }

/** Room under each page's last row for the mini player and the dock floating over it. */
private val PAGE_BOTTOM = CHROME_TAB_HEIGHT + CHROME_MINI_HEIGHT + 48.dp

/**
 * The window, laid out as the phone app lays out a screen of its size
 * (appLayout): a narrow one is the phone, with the dock and the mini player
 * floating over the page; a wider one has the rail down the side, and a wide
 * one the player itself docked on the right, folding into a strip.
 */
@Composable
fun DesktopApp() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var searching by remember { mutableStateOf(false) }
    // An album, playlist or artist opened from Home, over the page.
    var opened by remember { mutableStateOf<ShelfItem?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    var paneCollapsed by remember { mutableStateOf(false) }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val song = queue.getOrNull(index)
    val ui by AppSettings.ui.collectAsState()
    val chromeScroll = rememberChromeScroll()
    val select: (Int) -> Unit = { i -> tab = Tab.entries[i]; searching = false; opened = null; chromeScroll.expand() }
    val openSearch = { searching = true; opened = null; chromeScroll.expand() }
    val selected = if (searching || opened != null) null else tab.ordinal

    DesktopTheme(artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val layout = appLayout(maxWidth.value.toInt(), (maxHeight - DesktopWindow.topInset).value.toInt(), song != null, paneCollapsed)
                val rail = layout.nav == NavChrome.RAIL
                val haze = rememberHazeState()
                // The page makes room for the docked player as it slides in, rather than jumping.
                val end by animateDpAsState(
                    when (layout.nowPlaying) { Docking.PANE -> PANE_GUTTER; Docking.STRIP -> STRIP_GUTTER; else -> 0.dp },
                    tween(if (ui.reduceAnimation) 0 else 420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
                    label = "pane room",
                )
                val bottom = when {
                    !rail -> PAGE_BOTTOM
                    layout.nowPlaying == Docking.BAR -> CHROME_MINI_HEIGHT + 36.dp
                    else -> 24.dp
                }
                Box(
                    Modifier.fillMaxSize().hazeSource(haze).nestedScroll(chromeScroll)
                        .padding(top = DesktopWindow.topInset, start = if (rail) RAIL_GUTTER else 0.dp, end = end),
                ) {
                    CompositionLocalProvider(LocalPageBottom provides bottom, LocalChromeScroll provides chromeScroll) {
                        val width = layout.contentMaxWidthDp?.let { Modifier.widthIn(max = it.dp) } ?: Modifier
                        Box(Modifier.align(Alignment.TopCenter).then(width).fillMaxSize()) {
                            Pages(
                                page = opened ?: if (searching) SEARCH else tab,
                                still = ui.reduceAnimation,
                                transition = ui.pageTransition,
                                queue = queue,
                                index = index,
                                onOpen = { opened = it },
                                onBack = { opened = null },
                                onSettings = { select(Tab.SETTINGS.ordinal) },
                            )
                        }
                    }
                }

                CompositionLocalProvider(LocalHazeState provides haze.takeIf { !ui.reduceBlur }) {
                    if (!rail) {
                        BottomChrome(
                            inline = chromeScroll.inline,
                            tabs = TABS,
                            selected = selected,
                            onSelect = select,
                            onExpand = chromeScroll::expand,
                            searchSelected = searching,
                            onSearch = openSearch,
                            mini = song?.let { s -> { folded, m -> MiniPlayer(s, folded, m) { playerOpen = true } } },
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
                            motion = ui.dockMotion,
                            lens = ui.dockLens,
                            reduceMotion = ui.reduceAnimation,
                        )
                    } else {
                        NavRail(
                            tabs = TABS,
                            selected = selected,
                            onSelect = select,
                            searchSelected = searching,
                            onSearch = openSearch,
                            modifier = Modifier.align(Alignment.CenterStart).padding(top = DesktopWindow.topInset),
                            lens = ui.dockLens,
                            reduceMotion = ui.reduceAnimation,
                        )
                        if (layout.nowPlaying == Docking.BAR && song != null) {
                            MiniPlayer(
                                song, inline = false,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(start = RAIL_GUTTER, end = 12.dp, bottom = 12.dp)
                                    .fillMaxWidth().height(CHROME_MINI_HEIGHT),
                            ) { playerOpen = true }
                        }
                    }
                    val strip = rememberLast(song)
                    AnimatedVisibility(
                        visible = layout.nowPlaying == Docking.STRIP,
                        enter = fadeIn(tween(if (ui.reduceAnimation) 120 else 260, delayMillis = if (ui.reduceAnimation) 0 else 160)),
                        exit = fadeOut(tween(120)),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(top = DesktopWindow.topInset),
                    ) {
                        val playing by Player.playing.collectAsState()
                        NowPlayingStrip(
                            isPlaying = playing,
                            hasNext = index < queue.lastIndex,
                            onTogglePlay = Player::toggle,
                            onNext = Player::next,
                            onExpand = { paneCollapsed = false },
                        ) { m -> strip?.let { MiniPlayer(it, inline = true, modifier = m) { paneCollapsed = false } } }
                    }
                }

                // Wide windows: the player itself, docked on the right while a song is loaded.
                val docked = rememberLast(song)
                AnimatedVisibility(
                    visible = layout.nowPlaying == Docking.PANE,
                    enter = if (ui.reduceAnimation) fadeIn(tween(160)) else slideInHorizontally(tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it } + fadeIn(tween(220)),
                    exit = if (ui.reduceAnimation) fadeOut(tween(120)) else slideOutHorizontally(tween(320, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(260)),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(top = DesktopWindow.topInset),
                ) {
                    PlayerPaneFrame { docked?.let { NowPlaying(it, docked = true, onClose = { paneCollapsed = true }) } }
                }

                // Otherwise the player rises over everything, as the phone's does.
                val full = rememberLast(song)
                AnimatedVisibility(
                    visible = playerOpen && song != null && !layout.sidePlayer,
                    enter = PageMotion.playerIn(ui.playerMotion, ui.reduceAnimation),
                    exit = PageMotion.playerOut(ui.playerMotion, ui.reduceAnimation),
                ) { full?.let { NowPlaying(it, docked = false, onClose = { playerOpen = false }) } }
            }
        }
    }
}

/**
 * The open page, coming and going the way the phone's pages do for the
 * chosen page transition: an opened album or playlist pushes in over the
 * page, and going back pops it off again.
 */
@Composable
private fun Pages(
    page: Any,
    still: Boolean,
    transition: com.opentune.data.settings.PageTransition,
    queue: List<Song>,
    index: Int,
    onOpen: (ShelfItem) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    AnimatedContent(
        page,
        transitionSpec = {
            val back = initialState is ShelfItem && targetState !is ShelfItem
            if (back) PageMotion.popEnter(transition, still) togetherWith PageMotion.popExit(transition, still)
            else PageMotion.enter(transition, still) togetherWith PageMotion.exit(transition, still)
        },
        label = "page",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        when {
            shown is ShelfItem -> CollectionPage(shown, onBack = onBack)
            shown == SEARCH -> SearchPage()
            shown == Tab.HOME -> HomePage(onOpen = onOpen, onSettings = onSettings)
            shown == Tab.LIBRARY -> LibraryPage()
            shown == Tab.SETTINGS -> SettingsPage()
            else -> SongPage("Up next", queue, "The queue is empty.", current = index, onPlay = { _, i -> Player.playAt(i) })
        }
    }
}

/** The search page's key among the pages, beside the tabs and opened collections. */
private const val SEARCH = "search"

/** The last non-null [value], so something leaving can be drawn as it goes. */
@Composable
internal fun <T> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** Room under each page's last row, clear of whatever floats over the bottom of the window. */
internal val LocalPageBottom = staticCompositionLocalOf { PAGE_BOTTOM }

// ---- Pages -------------------------------------------------------------------

@Composable
private fun HomePage(onOpen: (ShelfItem) -> Unit, onSettings: () -> Unit) {
    var shelves by remember { mutableStateOf<List<Pages.Shelf>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        failed = false
        scope.launch { runCatching { Pages.home() }.onSuccess { shelves = it }.onFailure { failed = true } }
    }
    LaunchedEffect(Unit) { load() }
    val loaded = shelves
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PageTitle(greeting())
                Spacer(Modifier.weight(1f))
                // Settings in a glass circle by the greeting, as on the phone's Home.
                GlassCircle(Icons.Rounded.Settings, "Settings", onSettings, Modifier.padding(end = 20.dp))
            }
        }
        when {
            failed -> item { Message("Couldn't load Home", "Check your connection and try again.", action = "Try again" to ::load) }
            loaded == null -> items(3) { ShelfPlaceholder() }
            else -> itemsIndexed(loaded) { n, shelf ->
                Column(Modifier.appear(n), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shelf.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
                    val songs = remember(shelf) { shelf.items.mapNotNull(Pages::songOf) }
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(shelf.items) { item ->
                            Card(item) {
                                val song = Pages.songOf(item)
                                if (song != null) Player.playAll(songs, songs.indexOfFirst { it.videoId == song.videoId }) else onOpen(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A shelf's shape, shimmering, while Home loads. */
@Composable
private fun ShelfPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Placeholder(Modifier.padding(horizontal = 24.dp).width(180.dp).height(22.dp), RoundedCornerShape(8.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Placeholder(Modifier.size(160.dp), MaterialTheme.shapes.medium)
                    Placeholder(Modifier.width(120.dp).height(12.dp), RoundedCornerShape(6.dp))
                    Placeholder(Modifier.width(80.dp).height(10.dp), RoundedCornerShape(5.dp))
                }
            }
        }
    }
}

/** Good morning, afternoon or evening, as Home on the phone greets you. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun SearchPage() {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recentSearches by AppSettings.recentSearches.collectAsState()
    fun run() {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        AppSettings.addRecentSearch(q)
        searching = true
        failed = false
        scope.launch {
            runCatching { Pages.search(q) }.onSuccess { results = it }.onFailure { failed = true }
            searching = false
        }
    }
    // Search opens ready to type, as the phone's does.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle("Search")
        TextField(
            query, { query = it },
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(focus),
        )
        val found = results
        when {
            searching -> Loading()
            failed -> Message("Couldn't search", "Check your connection and try again.")
            found == null && recentSearches.isNotEmpty() -> RecentSearches(recentSearches) { query = it; run() }
            found == null -> Message("Find something to play", "Search for a song or an artist.", icon = Icons.Rounded.Search)
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

/** Liked songs, your playlists, and what you played lately. */
@Composable
private fun LibraryPage() {
    var shelf by remember { mutableStateOf(Shelf.LIKED) }
    var playlist by remember { mutableStateOf<String?>(null) }
    val liked by Library.liked.collectAsState()
    val played by Library.recent.collectAsState()
    val server by Subsonic.server.collectAsState()
    playlist?.let { id -> return PlaylistPage(id, onBack = { playlist = null }) }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle("Library")
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Shelf.entries.filter { it != Shelf.SERVER || server != null }.forEach { s -> FilterChip(shelf == s, { shelf = s }, label = { Text(s.label) }) }
        }
        AnimatedContent(
            shelf,
            transitionSpec = { (fadeIn(Motion.fade(240)) + slideInHorizontally(Motion.soft()) { if (targetState.ordinal > initialState.ordinal) 40 else -40 }) togetherWith fadeOut(Motion.fade(110)) },
            label = "library shelf",
            modifier = Modifier.fillMaxSize(),
        ) { shelf ->
        when (shelf) {
            Shelf.PLAYLISTS -> PlaylistGrid(onOpen = { playlist = it })
            Shelf.LIKED -> if (liked.isEmpty()) Message("No liked songs yet", "Click the heart on a song to keep it here.")
                else SongList(liked) { s, i -> Player.playAll(s, i) }
            Shelf.SERVER -> if (server != null) ServerShelf() else Message("No music server connected", "Connect one in Settings.")
            Shelf.RECENT -> if (played.isEmpty()) Message("Nothing played yet", "Songs you play show here.")
                else SongList(played) { s, i -> Player.playAll(s, i) }
        }
        }
    }
}

private enum class Shelf(val label: String) { LIKED("Liked songs"), PLAYLISTS("Playlists"), SERVER("Your server"), RECENT("Recent") }

@Composable
private fun SongPage(title: String, songs: List<Song>, empty: String, current: Int = -1, onPlay: (List<Song>, Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle(title)
        if (songs.isEmpty()) Message(empty, null) else SongList(songs, current, onPlay = onPlay)
    }
}

/** The songs of an album, playlist, artist or podcast, with a way back. */
@Composable
private fun CollectionPage(item: ShelfItem, onBack: () -> Unit) {
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
            .padding(top = 16.dp),
    ) {
        GlassCircle(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalPageBottom.current)) {
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isLiked = liked.any { it.videoId == song.videoId }
            val isCurrent = if (current >= 0) i == current else song.videoId == playingId
            var adding by remember { mutableStateOf(false) }
            val hover = remember { MutableInteractionSource() }
            Row(
                Modifier.appear(i).padding(horizontal = 8.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp))
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
