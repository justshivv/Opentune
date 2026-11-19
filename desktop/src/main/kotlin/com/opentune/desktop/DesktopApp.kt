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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.produceState
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
    NEW("Explore", Icons.Rounded.GridView, Icons.Rounded.GridView),
    SEARCH("Search", Icons.Rounded.Search, Icons.Rounded.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    QUEUE("Up next", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    TOGETHER("Listen together", Icons.Rounded.Groups, Icons.Rounded.Groups),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/** A playlist made here, opened from the sidebar or Library. */
internal data class PlaylistKey(val id: String)

/** What fills the page area: a tab, or something opened over it. */
private data class Page(val tab: Tab, val shelf: Shelf? = null, val opened: Any? = null)

/**
 * The window, as BitChord's desktop lays it out: a sidebar down the left
 * (pages, history, your playlists, Settings), the page with the player bar
 * frosted across its top, and Now playing (or the lyrics, or the queue) in a
 * column on the right when asked ([startPanel] opens with one, for
 * pictures). Clicking the song in the bar opens the full player.
 */
@Composable
fun DesktopApp(startPanel: SidePanel? = null, startAccountMenu: Boolean = false, startSearch: String? = null, startSearched: Boolean = false) {
    var page by remember { mutableStateOf(Page(if (startSearch != null) Tab.SEARCH else Tab.HOME)) }
    // Back goes through what was opened, the way it came.
    val history = remember { mutableStateListOf<Page>() }
    var query by remember { mutableStateOf(startSearch.orEmpty()) }
    var searches by remember { mutableStateOf(if (startSearched) 1 else 0) }
    var playerOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(startPanel) }
    // The sidebar folded or opened by hand; until then it follows the window's width.
    var foldPick by remember { mutableStateOf<Boolean?>(null) }
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
    var helpOpen by remember { mutableStateOf(false) }
    var accountOpen by remember { mutableStateOf(startAccountMenu) }
    // What a room has to say ("Only the host can skip"), as a notice.
    LaunchedEffect(Unit) { com.opentune.data.together.Together.notices.collect { Notice.show(it) } }
    LaunchedEffect(Unit) {
        Shortcuts.commands.collect { c ->
            when (c) {
                Shortcuts.Command.SEARCH -> { playerOpen = false; if (page.tab != Tab.SEARCH || page.opened != null) go(Page(Tab.SEARCH)) }
                Shortcuts.Command.SETTINGS -> { playerOpen = false; go(Page(Tab.SETTINGS)) }
                Shortcuts.Command.LYRICS -> panel = if (panel == SidePanel.LYRICS) null else SidePanel.LYRICS
                Shortcuts.Command.QUEUE -> panel = if (panel == SidePanel.QUEUE) null else SidePanel.QUEUE
                Shortcuts.Command.CLOSE -> if (helpOpen) helpOpen = false else if (accountOpen) accountOpen = false else playerOpen = false
                Shortcuts.Command.HELP -> helpOpen = true
                Shortcuts.Command.TOGETHER -> { playerOpen = false; go(Page(Tab.TOGETHER)) }
            }
        }
    }
    val sideSelected: Any? = when {
        page.opened is PlaylistKey -> page.opened
        page.opened != null -> null
        page.tab == Tab.LIBRARY -> page.shelf ?: Tab.LIBRARY
        else -> page.tab
    }

    DesktopTheme(artworkSeed = rememberArtworkSeed(song?.thumbnailUrl)) {
        // Windows 11's Mica or Acrylic behind the window, when it's on: the page lets it through.
        val material by WindowsFrame.activeMaterial.collectAsState()
        val chosenMaterial by WindowsFrame.material.collectAsState()
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        androidx.compose.runtime.LaunchedEffect(dark, chosenMaterial) { WindowsFrame.applyMaterial(dark) }
        val clear = material != WindowsFrame.Material.OFF
        val openAnywhere: (Any) -> Unit = { playerOpen = false; go(page.copy(opened = it)) }
        // The ink named outright: Surface works it out from its colour only when that is the
        // theme's own, and the see-through page over a material isn't, which left text black.
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = if (clear) MATERIAL_PAGE_ALPHA else 1f),
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            CompositionLocalProvider(
                LocalOpen provides openAnywhere,
                // Right-click menus in the theme's own light or dark.
                androidx.compose.foundation.LocalContextMenuRepresentation provides
                    if (dark) androidx.compose.foundation.DarkDefaultContextMenuRepresentation else androidx.compose.foundation.LightDefaultContextMenuRepresentation,
            ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val roomForPanel = maxWidth >= 1100.dp
                val panelShown = panel != null && song != null && roomForPanel
                // Folded to its rail on a narrow window, or as the button at its head last left it.
                val folded = foldPick ?: (maxWidth < 900.dp)
                // The sidebar eases between its widths over 380ms, as the web's does; the room it takes
                // changes in one step and the page slides from where it was (see below), so nothing on
                // the page is laid out again on each frame of it.
                val fold by animateFloatAsState(
                    if (folded) 1f else 0f,
                    if (ui.reduceAnimation) androidx.compose.animation.core.snap() else tween(380, easing = Motion.Settle),
                    label = "fold",
                )
                val haze = rememberHazeState()
                // Behind the whole window, so the see-through sidebar and side column take its colour
                // as well as the page; a source for the top bar's blur under the page's own.
                Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 0f)) { WindowBackdrop(song?.thumbnailUrl, clear) }
                Row(Modifier.fillMaxSize()) {
                    Sidebar(
                        sideSelected, folded = folded, fold = fold, haze = haze.takeIf { !ui.reduceBlur }, onFold = { foldPick = !folded },
                        query = query,
                        // Typing in the sidebar's field opens the Search page and drives it.
                        onQuery = { query = it; if (it.isNotBlank() && (page.tab != Tab.SEARCH || page.opened != null)) { playerOpen = false; go(Page(Tab.SEARCH)) } },
                        onSearch = { if (page.tab != Tab.SEARCH || page.opened != null) go(Page(Tab.SEARCH)); searches++ },
                    ) { key ->
                        when (key) {
                            is Tab -> go(Page(key))
                            is Shelf -> go(Page(Tab.LIBRARY, key))
                            is PlaylistKey -> go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, key))
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                            // Where the sidebar is drawn this frame against the room it now takes.
                            val drawn = androidx.compose.ui.unit.lerp(SIDEBAR_WIDTH, SIDEBAR_FOLDED, fold)
                            translationX = (drawn - if (folded) SIDEBAR_FOLDED else SIDEBAR_WIDTH).toPx()
                        },
                    ) {
                        // The page is what the frosted player bar blurs; every page starts below it.
                        CompositionLocalProvider(
                            LocalPageTop provides TOP_BAR_HEIGHT + DesktopWindow.topInset,
                            LocalPageBottom provides 40.dp,
                            com.opentune.ui.components.LocalHazeState provides haze.takeIf { !ui.reduceBlur },
                        ) {
                            Box(Modifier.fillMaxSize().hazeSource(haze, zIndex = 1f)) {
                                Pages(
                                    page = page,
                                    still = ui.reduceAnimation,
                                    queue = queue,
                                    index = index,
                                    query = query,
                                    onQuery = { query = it },
                                    searches = searches,
                                    onSearch = { searches++ },
                                    onOpen = { go(page.copy(opened = it)) },
                                    onOpenAny = { go(page.copy(opened = it)) },
                                    onShelf = { go(Page(Tab.LIBRARY, it)) },
                                    onPlaylist = { go(Page(Tab.LIBRARY, Shelf.PLAYLISTS, PlaylistKey(it))) },
                                    onBack = { back() },
                                    fieldInSidebar = !folded,
                                )
                            }
                            TopBar(
                                song, canGoBack = history.isNotEmpty(), onBack = back,
                                panel = panel.takeIf { panelShown },
                                onPanel = { p -> panel = if (panel == p && panelShown) null else p },
                                onOpenPlayer = { playerOpen = true },
                                modifier = Modifier.align(Alignment.TopCenter),
                                panelRoom = roomForPanel,
                                onHome = { go(Page(Tab.HOME)) },
                                onQueuePage = { go(Page(Tab.QUEUE)) },
                                onAccount = { accountOpen = !accountOpen },
                                accountOpen = accountOpen,
                            )
                            // Songs picked on the page: what can be done with them, over its foot.
                            PageSelectionBar(page, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    val shown = rememberLast(panel)
                    // The column slides in from the window's edge over 300ms and back out over 220ms, as
                    // the web's side panel does. It takes its room in one step, so the page beside it
                    // isn't laid out again on every frame of the slide.
                    AnimatedVisibility(
                        visible = panelShown,
                        enter = if (ui.reduceAnimation) fadeIn(tween(140)) else slideInHorizontally(tween(300, easing = Motion.Panel)) { it } + fadeIn(tween(200)),
                        exit = if (ui.reduceAnimation) fadeOut(tween(120)) else slideOutHorizontally(tween(220, easing = Motion.Exit)) { it } + fadeOut(tween(220, easing = Motion.Exit)),
                    ) {
                        if (song != null && shown != null) RightPanel(
                            shown, song, queue, index, haze = haze.takeIf { !ui.reduceBlur },
                            onPanel = { panel = it }, onOpenArtist = { go(page.copy(opened = it)) },
                        )
                    }
                }

                // The account menu, under the picture at the top bar's right end.
                ChromeAccountMenu(
                    accountOpen, haze.takeIf { !ui.reduceBlur }, onDismiss = { accountOpen = false },
                    onSettings = { go(Page(Tab.SETTINGS)) },
                    onAccounts = { ChromeSettingsJump.accounts.value = true; go(Page(Tab.SETTINGS)) },
                )

                // The full player rises over everything (see PlayerOverlay).
                val full = rememberLast(song)
                PlayerOverlay(open = playerOpen && song != null, cover = song?.thumbnailUrl, onClose = { playerOpen = false }) {
                    full?.let {
                        NowPlaying(
                            it, docked = false, onClose = { playerOpen = false },
                            onOpenTogether = { playerOpen = false; go(Page(Tab.TOGETHER)) },
                        )
                    }
                }

                NoticePill(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                if (helpOpen) ShortcutsDialog { helpOpen = false }
            }
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
    query: String,
    onQuery: (String) -> Unit,
    searches: Int,
    onSearch: () -> Unit,
    onOpen: (ShelfItem) -> Unit,
    onOpenAny: (Any) -> Unit,
    onShelf: (Shelf) -> Unit,
    onPlaylist: (String) -> Unit,
    onBack: () -> Unit,
    fieldInSidebar: Boolean = false,
) {
    val pxPerDp = androidx.compose.ui.platform.LocalDensity.current.density
    AnimatedContent(
        page,
        transitionSpec = {
            // A desktop page changes with a fade and a short rise, as the web player's pages come in
            // (10px over 320ms on its emphasized curve), not the phone's full-page slides and scales:
            // moving a whole window of covers each frame stutters.
            val back = initialState.opened != null && targetState.opened == null
            val rise = (if (still) 0 else if (back) -8 else 10) * pxPerDp
            (fadeIn(tween(if (still) 120 else 220, delayMillis = if (still) 0 else 30)) +
                slideInVertically(tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { rise.toInt() }) togetherWith
                fadeOut(tween(if (still) 90 else 120))
        },
        label = "page",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val opened = shown.opened
        when {
            opened is ShelfItem && opened.browseId?.startsWith("UC") == true -> ArtistView(opened, onOpen)
            opened is ShelfItem -> CollectionPage(opened)
            opened is PlaylistKey -> PlaylistPage(opened.id, onBack = onBack)
            opened is MoodKey -> MoodPage(opened, onOpen)
            opened is DailyMixes.Mix -> MixPage(opened)
            shown.tab == Tab.SEARCH -> SearchPage(query, onQuery, searches, onSearch, onOpenAny, fieldInSidebar)
            shown.tab == Tab.NEW -> NewPage(onOpen)
            shown.tab == Tab.HOME -> HomePage(onOpen = onOpen, onOpenAny = onOpenAny)
            shown.tab == Tab.LIBRARY -> shown.shelf?.let { LibraryPage(it, onShelf, onPlaylist) }
                ?: LibraryHome(onShelf = onShelf, onPlaylist = onPlaylist, onOpenAny = onOpenAny)
            shown.tab == Tab.SETTINGS -> SettingsPage()
            shown.tab == Tab.TOGETHER -> ListenTogetherPage()
            else -> QueuePage(queue, index)
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
/** Room every page keeps clear at its top, under the frosted player bar. */
internal val LocalPageTop = staticCompositionLocalOf { 0.dp }

/** How much of the page colour lies over Windows' material, keeping text on it readable. */
private const val MATERIAL_PAGE_ALPHA = 0.45f

// ---- Pages -------------------------------------------------------------------

/**
 * Search. The query is typed in the sidebar's field (on a folded sidebar, in a field at the top
 * of the page). Before a search, what you searched for lately as chips and every mood and genre
 * as coloured tiles to browse. While typing, YouTube Music's suggestions, then a few results for
 * what's typed so far; after Enter, every kind found (songs, artists, albums, playlists,
 * videos) as flat rows under their headings.
 */
@Composable
internal fun SearchPage(
    query: String,
    onQuery: (String) -> Unit,
    searches: Int,
    onSearch: () -> Unit,
    onOpenAny: (Any) -> Unit,
    fieldInSidebar: Boolean = false,
) {
    // What was found last, so coming back to Search shows it again rather than asking afresh.
    var results by remember { mutableStateOf(SearchMemory.found.takeIf { SearchMemory.shownFor == query.trim() }) }
    var shownFor by remember { mutableStateOf(if (results != null) SearchMemory.shownFor else "") }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val recentSearches by AppSettings.recentSearches.collectAsState()
    val moods by produceState(emptyList<com.opentune.data.model.MoodGenre>()) { value = runCatching { Pages.moods() }.getOrDefault(emptyList()) }
    LaunchedEffect(searches) {
        // Each Enter once: opening the page again doesn't search again.
        if (searches == 0 || searches == SearchMemory.handled) return@LaunchedEffect
        SearchMemory.handled = searches
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        AppSettings.addRecentSearch(q)
        searching = true
        failed = false
        runCatching { Pages.searchAll(q) }
            .onSuccess { results = it; shownFor = q; SearchMemory.found = it; SearchMemory.shownFor = q }
            .onFailure { failed = true }
        searching = false
    }
    // While typing, until Enter searches: YouTube Music's suggestions, and a few of its results.
    val typed = query.trim()
    val typing = typed.isNotEmpty() && typed != shownFor && !searching
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var quick by remember { mutableStateOf(emptyList<ChromeSearchHit>()) }
    LaunchedEffect(typed, shownFor) {
        if (typed.isEmpty() || typed == shownFor) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(200)
        suggestions = runCatching { Pages.suggestions(typed) }.getOrDefault(emptyList()).filterNot { it.equals(typed, ignoreCase = true) }.take(6)
    }
    LaunchedEffect(typed, shownFor) {
        if (typed.isEmpty() || typed == shownFor) { quick = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(380)
        quick = runCatching { ChromeSearchHits.quick(typed) }.getOrDefault(emptyList())
    }
    // Cleared: back to browsing.
    LaunchedEffect(query) { if (query.isBlank()) { results = null; shownFor = "" } }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val askedForFocus by Shortcuts.searchFocus.collectAsState()
    LaunchedEffect(askedForFocus, fieldInSidebar) { if (!fieldInSidebar) runCatching { focus.requestFocus() } }
    val found = results
    // Which kinds are showing all they found rather than the first few.
    val expanded = remember(shownFor) { mutableStateMapOf<String, Boolean>() }
    ScrollPage("Search", spacing = 8.dp) {
        if (!fieldInSidebar) item(key = "field") { SearchField(query, onQuery, onSearch, focus) }
        when {
            typing -> {
                if (suggestions.isNotEmpty()) item(key = "suggestions") {
                    Column(Modifier.padding(horizontal = GUTTER - 12.dp)) {
                        suggestions.forEach { s ->
                            ChromeSuggestionRow(s, onPick = { onQuery(s); onSearch() }, onFill = { onQuery(s) })
                        }
                    }
                }
                if (quick.isNotEmpty()) {
                    if (suggestions.isNotEmpty()) item(key = "quick-line") { ChromeSearchHairline() }
                    val songs = quick.mapNotNull { (it as? ChromeSearchHit.Track)?.song }
                    items(quick, key = { "q:" + it.key }) { hit ->
                        Box(Modifier.padding(horizontal = GUTTER - 12.dp)) {
                            when (hit) {
                                is ChromeSearchHit.Track -> ChromeSongResultRow(hit.song, songs, songs.indexOf(hit.song), kind = "Song")
                                is ChromeSearchHit.Item -> ChromeItemResultRow(hit.item, kind = hit.kind) { ChromeSearchHits.open(hit.item, onOpenAny) }
                            }
                        }
                    }
                }
            }
            searching -> item(key = "loading") { Loading() }
            failed -> item(key = "failed") { Message("Couldn't search", "Check your connection and try again.") }
            found == null -> {
                if (recentSearches.isNotEmpty()) item(key = "recent") { Box(Modifier.padding(top = 14.dp)) { RecentSearches(recentSearches) { onQuery(it); onSearch() } } }
                if (moods.isNotEmpty()) item(key = "browse") {
                    Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Browse all", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = GUTTER))
                        BrowseTiles(moods) { onOpenAny(MoodKey(it.title, it.browseId, it.params)) }
                    }
                }
                if (recentSearches.isEmpty() && moods.isEmpty()) item(key = "hint") { Message("Find something to play", "Type a song or an artist and press Enter.", icon = Icons.Rounded.Search) }
            }
            found.isEmpty -> item(key = "none") { Message("No results for “$shownFor”", "Try different words.", icon = Icons.Rounded.SearchOff) }
            else -> {
                // Each kind under its heading, the first few at once and the rest on asking.
                fun section(title: String, count: Int, few: Int, row: androidx.compose.foundation.lazy.LazyListScope.(Int) -> Unit) {
                    if (count == 0) return
                    item(key = "head:$title") {
                        ChromeResultsHead(title, more = count > few, open = expanded[title] == true) { expanded[title] = expanded[title] != true }
                    }
                    val shown = if (expanded[title] == true) count else minOf(count, few)
                    for (i in 0 until shown) row(i)
                }
                section("Songs", found.songs.size, 5) { i ->
                    item(key = "song:$i:${found.songs[i].videoId}") {
                        Box(Modifier.padding(horizontal = GUTTER - 12.dp)) { ChromeSongResultRow(found.songs[i], found.songs, i) }
                    }
                }
                listOf("Artists" to found.artists, "Albums" to found.albums, "Playlists" to found.playlists, "Videos" to found.videos).forEach { (title, list) ->
                    section(title, list.size, 4) { i ->
                        item(key = "$title:$i:${list[i].browseId ?: list[i].videoId}") {
                            Box(Modifier.padding(horizontal = GUTTER - 12.dp)) { ChromeItemResultRow(list[i]) { ChromeSearchHits.open(list[i], onOpenAny) } }
                        }
                    }
                }
            }
        }
    }
}

/** The last search shown, kept while the app runs, so Search opens on it again. */
private object SearchMemory {
    var handled = -1
    var shownFor = ""
    var found: Pages.Found? = null
}

/** The search box on the page itself, while the sidebar is folded to its rail: flat, rounded, with a clear button once there's something in it. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, focus: androidx.compose.ui.focus.FocusRequester) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = ChromeInk.secondary()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(horizontal = GUTTER).padding(top = 16.dp, bottom = 8.dp).widthIn(max = 720.dp).fillMaxWidth().height(48.dp)
            .clip(shape).background(ChromeInk.field()).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search", color = muted, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .then(androidx.compose.ui.Modifier.chromeLabel("Search"))
                    .onPreviewKeyEvent { e ->
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                            (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter)
                        ) { onSearch(); true } else false
                    },
            )
        }
        if (query.isNotEmpty()) HoverIcon(Icons.Rounded.Close, "Clear", size = 30.dp, iconSize = 18.dp) { onQuery("") }
    }
}

/** Every mood and genre as Explore's tiles: a band of its colour beside its cover dyed in it, in a grid that fills the width. */
@Composable
private fun BrowseTiles(moods: List<com.opentune.data.model.MoodGenre>, onOpen: (com.opentune.data.model.MoodGenre) -> Unit) {
    MoodGrid(moods, onOpen)
}

/** What you searched for lately, as chips; each comes off with its cross. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(queries: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = GUTTER), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = AppSettings::clearRecentSearches) { Text("Clear") }
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            queries.forEach { q ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val onSurface = MaterialTheme.colorScheme.onSurface
                val fill by animateColorAsState(onSurface.copy(alpha = if (hovered) 0.14f else 0.08f), tween(140), label = "recent chip")
                Row(
                    Modifier.clip(CircleShape).background(fill).hoverable(hover).clickable(interactionSource = hover, indication = null) { onPick(q) }
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(q, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                    HoverIcon(Icons.Rounded.Close, "Remove", size = 24.dp, iconSize = 14.dp) { AppSettings.removeRecentSearch(q) }
                }
            }
        }
    }
}

/**
 * One of the Library's lists: liked songs and what you played as collection
 * pages with a cover of their own, your playlists as tiles, your server.
 */
@Composable
internal fun LibraryPage(shelf: Shelf, onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit) = LibraryShelfPage(shelf, onShelf, onPlaylist)

/** A list's own cover where it has no artwork: its icon on a gradient. */
@Composable
internal fun IconCover(icon: ImageVector, colours: List<Color>, modifier: Modifier) {
    Box(modifier.background(Brush.linearGradient(colours)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(84.dp))
    }
}

internal enum class Shelf(val label: String) { LIKED("Liked songs"), PLAYLISTS("Playlists"), SERVER("Your server"), RECENT("Recently played") }

/**
 * An album, playlist or podcast: the head (its cover large, what it is, who made it, the year
 * and how long it runs), then its tracks.
 */
@Composable
internal fun CollectionPage(item: ShelfItem) {
    var songs by remember(item) { mutableStateOf<List<Song>?>(null) }
    var failed by remember(item) { mutableStateOf(false) }
    LaunchedEffect(item) {
        runCatching { Pages.songsOf(item.browseId!!) }.onSuccess { songs = it }.onFailure { failed = true }
    }
    val id = item.browseId.orEmpty()
    val kinds = setOf("Album", "Single", "EP", "Playlist", "Podcast")
    val album = id.startsWith("MPRE") || id.startsWith("OLAK")
    val kind = when {
        id.startsWith("MPSP") -> "Podcast"
        album -> item.subtitle.substringBefore(" • ").takeIf { it in setOf("Album", "Single", "EP") } ?: "Album"
        else -> "Playlist"
    }
    // "Album • The Weeknd • 2020": who made it on its own line, the year with the facts under it.
    val parts = item.subtitle.split(" • ").map { it.trim() }.filter { it.isNotEmpty() && it !in kinds }
    val year = parts.firstOrNull { it.length == 4 && it.all(Char::isDigit) }
    val owner = parts.filter { it != year && !it.endsWith(" views") && !it.endsWith(" songs") && !it.endsWith(" tracks") }.joinToString(" • ")
    CollectionLayout(item.title, item.thumbnailUrl, songs, failed, album = item.title.takeIf { album }) {
        CollectionHero(
            cover = { m -> CoverFill(item.thumbnailUrl, m) },
            coverUrl = item.thumbnailUrl, kind = listOfNotNull(kind, year).joinToString(" • "), title = item.title,
            subtitle = owner,
            songs = songs,
            extra = { ListMoreButton(songs.orEmpty(), linkOf(id)) },
        )
    }
}

/** The YouTube Music address of an album, playlist or podcast, to share. */
internal fun linkOf(browseId: String): String? = when {
    browseId.isBlank() -> null
    browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
    browseId.startsWith("PL") || browseId.startsWith("RD") || browseId.startsWith("OLAK") -> "https://music.youtube.com/playlist?list=$browseId"
    else -> "https://music.youtube.com/browse/$browseId"
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
            val pick = rememberRowPick(songs, i)
            SongContextMenu(song) {
            Row(
                Modifier.appear(i, since).padding(horizontal = 8.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp))
                    .then(pick?.capture ?: Modifier)
                    .clickable(interactionSource = hover, indication = null) { pick?.click { onPlay(songs, i) } ?: onPlay(songs, i) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pick != null) PickSlot(pick, song.title, hover)
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
                SongMoreButton(song, size = 36.dp)
                if (onRemove != null) {
                    IconButton(onClick = { onRemove(song) }) {
                        Icon(Icons.Rounded.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        }
    }
}

/**
 * A card: the cover, which eases a little larger inside its frame under the
 * pointer while a shade comes up and a play button rises into its corner;
 * the title and grey line under it. An artist's is round.
 */
@Composable
internal fun Card(item: ShelfItem, onClick: () -> Unit) {
    val round = item.browseId?.startsWith("UC") == true
    val align = if (round) TextAlign.Center else TextAlign.Start
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(if (hovered) 1.07f else 1f, tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "card zoom")
    val shade by animateFloatAsState(if (hovered) 1f else 0f, tween(220), label = "card shade")
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    Column(
        Modifier.width(172.dp).hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick).padding(bottom = 4.dp),
    ) {
        Box(Modifier.size(172.dp).shadow(if (hovered) 18.dp else 6.dp, shape).clip(shape)) {
            Cover(item.thumbnailUrl, 172.dp, shape, Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom })
            Box(Modifier.matchParentSize().graphicsLayer { alpha = shade }.background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            androidx.compose.animation.AnimatedVisibility(
                hovered, enter = fadeIn(Motion.fade(160)) + slideInVertically(Motion.soft()) { it / 2 }, exit = fadeOut(Motion.fade(120)) + slideOutVertically(tween(160)) { it / 2 },
                modifier = Modifier.align(if (round) Alignment.Center else Alignment.BottomEnd).padding(10.dp),
            ) {
                Box(Modifier.size(44.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
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
