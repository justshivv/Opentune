package com.opentune.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.opentune.data.account.AccountStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.ContentFilter
import com.opentune.data.history.History
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.data.settings.AppSettings
import com.opentune.ui.browse.SongActions
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.GlassIconButton
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.Shelf
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.components.SongListItem
import com.opentune.ui.rememberLoader
import com.opentune.ui.toSong
import com.opentune.ui.type
import com.opentune.ui.cardMenu

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    actions: SongActions,
    onItemClick: (ShelfItem) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the page that names a song from what the microphone hears. */
    onRecognize: (() -> Unit)? = null,
    /** The full player is open over the page, so nothing on it needs to move. */
    covered: Boolean = false,
    /** Opens every Speed dial pick on a page of its own. */
    onOpenSpeedDial: () -> Unit = {},
) {
    val loader = rememberLoader("home") { MusicRepository.home() }
    val state by loader.state.collectAsState()
    val refreshing by loader.refreshing.collectAsState()
    val moreAll by MusicRepository.homeMore.collectAsState()
    val library by AppSettings.library.collectAsState()
    val hideExplicit = library.hideExplicit
    // What the user hid is taken off every song shelf and out of the mixes.
    val hidden by com.opentune.data.library.LibraryStore.hidden.collectAsState()
    val more = remember(moreAll, hideExplicit, hidden) { hidden.shelves(ContentFilter.shelves(moreAll, hideExplicit)) }
    val records by History.records.collectAsState()
    val recents = remember(records, hideExplicit) { ContentFilter.songs(History.recents(records, 100), hideExplicit) }
    // Not saved with the page: a page opened from inside it (an artist, from a
    // song's menu) is left with one back press, not one that brings the list up again.
    var historyOpen by remember { mutableStateOf(false) }
    val first = (state as? UiState.Success<List<HomeShelf>>)?.data?.let { hidden.shelves(ContentFilter.shelves(it, hideExplicit)) }.orEmpty()
    val allShelves = first + more
    val feed = remember(allShelves) { arrangeHomeShelves(allShelves) }
    // YouTube Music's own Speed dial when it sends one (signed in), else the songs
    // played most on this phone, with what's hidden or filtered left out.
    val localDial = remember(records, hidden, hideExplicit) {
        hidden.shelves(ContentFilter.shelves(listOf(HomeShelf(com.opentune.data.history.SpeedDial.TITLE, com.opentune.data.history.SpeedDial.fromHistory(records))), hideExplicit))
            .firstOrNull()?.items.orEmpty()
    }
    val speedDial = feed.speedDial?.items?.takeIf { it.size >= SPEED_DIAL_MIN } ?: localDial
    androidx.compose.runtime.SideEffect { SpeedDialItems.items = speedDial }
    // With little history, mixes from the followed artists fill in (see HomeSeed);
    // what the user hid stays out of both.
    val seedsAll by MusicRepository.seeds.collectAsState()
    val seeds = remember(seedsAll, hidden) {
        seedsAll.filterNot { hidden.hidesArtist(it.name, it.browseId) }
            .map { it.copy(top = hidden.songs(it.top), radio = hidden.songs(it.radio)) }
    }
    val mixes = remember(records, seeds, hidden) {
        com.opentune.data.reco.HomeSeed.mixes(com.opentune.data.history.DailyMixes.build(records, hidden = hidden), seeds, records.size)
    }
    val ui by AppSettings.ui.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    // Signing in or out changes what YouTube Music recommends; fetch again then.
    var seenSignedIn by remember { mutableStateOf(signedIn) }
    LaunchedEffect(signedIn) {
        if (signedIn != seenSignedIn) {
            seenSignedIn = signedIn
            MusicRepository.refreshHomeRecommendations()
            loader.reload(keepContent = true)
        }
    }

    val page = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val app = LocalActivityLifecycle()
    if (historyOpen) {
        // Going to another page from inside the list closes it at once, rather
        // than leaving it over the new page while Home slides away. The app
        // merely going to the background leaves it open.
        LaunchedEffect(page, app) {
            val resumed = androidx.lifecycle.Lifecycle.State.RESUMED
            combine(page.currentStateFlow, app?.currentStateFlow ?: flowOf(resumed)) { p, a -> a.isAtLeast(resumed) && !p.isAtLeast(resumed) }
                .first { it }
            historyOpen = false
        }
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { historyOpen = false }) {
            // Frosted behind, like the long-press menus and the other sheets.
            com.opentune.ui.components.FrostBehind(enabled = !com.opentune.data.settings.AppSettings.ui.collectAsState().value.reduceBlur)
            if (recents.isEmpty()) Text("Your recently played songs will appear here", Modifier.padding(24.dp))
            else Recents(recents, ui.recentsAsGrid, actions) {
                AppSettings.updateUi { it.copy(recentsAsGrid = !it.recentsAsGrid) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // One or two of the big cards below move their cover while the page sits still.
    val covers = rememberMovingCovers(LocalLayoutDirection.current == LayoutDirection.Rtl, covered)
    val listState = rememberLazyListState()
    LaunchedEffect(listState, covers) {
        snapshotFlow { listState.isScrollInProgress }.collect { covers.pageScrolling = it }
    }
    // Home tapped again: back up to the top, and the page refreshes with its pull mark.
    LaunchedEffect(listState) {
        com.opentune.ui.TabEvents.reselected.collect { r ->
            if (r != "home") return@collect
            if (listState.firstVisibleItemIndex > 12) listState.scrollToItem(6)
            listState.animateScrollToItem(0)
            MusicRepository.refreshHomeRecommendations()
            loader.reload(keepContent = true)
        }
    }

    com.opentune.ui.components.MarkRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { MusicRepository.refreshHomeRecommendations(); loader.reload(keepContent = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().nestedScroll(covers.scrollWatch)) {
            item(key = "top") {
                // A greeting for the time of day, by name when signed in, with the
                // account (and settings) one tap away on the right.
                val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
                val greeting = when (hour) {
                    in 5..11 -> "Good morning"
                    in 12..16 -> "Good afternoon"
                    in 17..21 -> "Good evening"
                    else -> "Late night"
                }
                // A name only when there really is one: the signed-in account's, or the
                // one entered for Listen together. Otherwise the greeting stands alone.
                val firstName = (account?.name ?: com.opentune.data.together.Together.savedName)
                    .substringBefore(' ').trim().takeIf { it.isNotBlank() }
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                ) {
                    // The greeting sits right over the name, the buttons beside the pair.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            if (firstName != null) {
                                Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    firstName,
                                    style = MaterialTheme.typography.displaySmall,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            } else {
                                Text(greeting, style = MaterialTheme.typography.displaySmall, maxLines = 2)
                            }
                        }
                        if (onRecognize != null) {
                            GlassIconButton(Icons.Rounded.GraphicEq, "Name a song playing nearby", onRecognize, size = 48.dp)
                        }
                        com.opentune.ui.components.WithUpdateDot {
                            GlassIconButton(Icons.Rounded.Person, "Account and settings", onOpenSettings, size = 48.dp) {
                                val photo = account?.thumbnailUrl
                                if (photo != null) Artwork(photo, Modifier.size(48.dp), CircleShape)
                                else Icon(Icons.Rounded.Person, "Account and settings", Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
            item(key = "festival") { FestivalCard() }
            if (ui.speedDial && speedDial.size >= SPEED_DIAL_MIN) {
                item(key = "speed-dial") {
                    SpeedDialSection(
                        speedDial,
                        strapline = feed.speedDial?.subtitle?.takeIf { it.isNotBlank() }
                            ?: account?.name?.substringBefore(' ').orEmpty(),
                        avatarUrl = account?.thumbnailUrl,
                        onItemClick = onItemClick,
                        onOpenAll = onOpenSpeedDial,
                    )
                }
            }
            item(key = "quick-picks") {
                val historyAction: @Composable () -> Unit = {
                    IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.History, "Recently played", Modifier.size(22.dp))
                    }
                }
                val picks = feed.quickPicks
                if (picks != null) QuickPicks(picks.copy(title = "Quick picks", subtitle = ""), onItemClick, historyAction)
                else SectionHeader("Quick picks", action = historyAction)
            }
            if (mixes.isNotEmpty()) {
                item(key = "mixes") {
                    // Before any listening, every mix comes from the followed artists.
                    val seededOnly = mixes.all { it.id.startsWith("seed:") }
                    MixesShelf(mixes, actions, if (seededOnly) "From the artists you follow, until you've played more" else MIXES_LINE)
                }
            }
            when (val s = state) {
                is UiState.Loading -> items(3) { ShelfPlaceholder() }
                is UiState.Error -> item(key = "error") {
                    ErrorState(s.message, onRetry = { loader.reload() }, modifier = Modifier.padding(top = 24.dp))
                }
                is UiState.Success -> {
                    itemsIndexed(feed.cards, key = { i, shelf -> "shelf:$i:${shelf.title}" }, contentType = { _, _ -> "cards" }) { i, shelf ->
                        HomeShelfView(shelf, onItemClick, songRows = false, covers = covers, coverKey = "shelf:$i")
                    }
                }
            }
        }
    }
}

/**
 * Recently played, from this device's history: a paged list four rows deep,
 * or a row of covers. The choice is remembered.
 */
@Composable
private fun Recents(songs: List<Song>, asGrid: Boolean, actions: SongActions, onToggleLayout: () -> Unit) {
    Column {
        SectionHeader("Recents", action = {
            IconButton(onClick = onToggleLayout) {
                Icon(
                    if (asGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    if (asGrid) "Show as list" else "Show as grid",
                )
            }
        })
        AnimatedContent(asGrid, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "recents") { grid ->
            if (grid) {
                val rowState = rememberLazyListState()
                LazyRow(
                    state = rowState,
                    flingBehavior = rememberSnapFlingBehavior(rowState, snapPosition = SnapPosition.Start),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    itemsIndexed(songs, key = { _, song -> song.videoId }) { i, song ->
                        ItemCard(song.title, song.artist, song.thumbnailUrl, null, onClick = { actions.playAll(songs, i, false, "Recents") }, explicit = song.isExplicit == true, menu = com.opentune.ui.components.CardMenu.ForSong(song))
                    }
                }
            } else {
                val pages = rememberPagerState(pageCount = { (songs.size + 3) / 4 })
                HorizontalPager(
                    state = pages,
                    modifier = Modifier.fillMaxWidth().height(64.dp * 4),
                    verticalAlignment = Alignment.Top,
                    // Leave the next page's covers peeking at the edge, so it reads as scrollable.
                    contentPadding = PaddingValues(end = 40.dp),
                ) { page ->
                    Column(Modifier.fillMaxWidth()) {
                        for (i in page * 4 until minOf(page * 4 + 4, songs.size)) {
                            val song = songs[i]
                            SongListItem(
                                song = song,
                                onClick = { actions.playAll(songs, i, false, "Recents") },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                isCurrent = song.videoId == actions.currentVideoId,
                                isPlaying = actions.isPlaying,
                                onPlayNext = { actions.playNext(song) },
                                onAddToQueue = { actions.addToQueue(song) },
                                // A sideways drag changes pages; Play next and
                                // Add to queue stay in the long-press menu.
                                swipeToQueue = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Songs-only shelves of four or more are laid out as quick picks. */
private fun HomeShelf.isQuickPicks(): Boolean =
    items.size >= 4 && items.all { it.videoId != null && it.browseId == null }

/** Home uses a single paged track list followed by collection and track cards. */
internal data class HomeSections(val quickPicks: HomeShelf?, val cards: List<HomeShelf>, val speedDial: HomeShelf? = null)

/** Fewer picks than this and Speed dial isn't shown: there's too little to go on yet. */
private const val SPEED_DIAL_MIN = 3

/**
 * One track list, then collections and varied card shelves. Keep every recommendation.
 * The picks are the followed artists' seed while there's little history (it's
 * only made then), else the radio off the last song, else the first songs shelf.
 */
internal fun arrangeHomeShelves(shelves: List<HomeShelf>): HomeSections {
    val all = shelves.filter { it.items.isNotEmpty() }
    // YouTube Music's Speed dial has its own place at the top, not among the cards.
    val speedDial = all.firstOrNull { com.opentune.data.history.SpeedDial.isSpeedDial(it.title) }
    val available = all.filterNot { com.opentune.data.history.SpeedDial.isSpeedDial(it.title) }
    val pickIndex = listOf(com.opentune.data.reco.HomeSeed.TITLE_PREFIX, "Because you played").firstNotNullOfOrNull { prefix ->
        available.indexOfFirst { it.isQuickPicks() && it.title.startsWith(prefix, ignoreCase = true) }.takeIf { it >= 0 }
    } ?: available.indexOfFirst { it.isQuickPicks() }
    val remaining = available.filterIndexed { index, _ -> index != pickIndex }
    val (collections, tracks) = remaining.partition { shelf -> shelf.items.any { it.browseId != null } }
    // Playlist/mix collections first, retaining the provider's order within each kind.
    val orderedCollections = collections.sortedBy { shelf ->
        if (shelf.items.any { it.browseId?.startsWith("VL") == true }) 0 else 1
    }
    val cards = buildList {
        for (index in 0 until maxOf(orderedCollections.size, tracks.size)) {
            orderedCollections.getOrNull(index)?.let(::add)
            tracks.getOrNull(index)?.let(::add)
        }
    }
    return HomeSections(available.getOrNull(pickIndex), cards, speedDial)
}

/**
 * A shelf of cards, or quick picks for a shelf of songs. Given Home's
 * [covers], its song and album cards can move their covers, under [coverKey].
 */
@Composable
fun HomeShelfView(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit, modifier: Modifier = Modifier, songRows: Boolean = true, covers: MovingCovers? = null, coverKey: String = "") {
    Box(modifier) {
        if (songRows && shelf.isQuickPicks()) {
            QuickPicks(shelf, onItemClick)
        } else {
            if (covers == null) {
                Shelf(title = shelf.title, subtitle = shelf.subtitle, items = shelf.items) { item ->
                    ItemCard(item.title, item.subtitle, item.thumbnailUrl, item.type(), onClick = { onItemClick(item) }, explicit = item.explicit, menu = item.cardMenu())
                }
            } else {
                // By place in the shelf, since a shelf can hold the same item twice.
                val indexed = remember(shelf.items) { shelf.items.withIndex().toList() }
                Shelf(title = shelf.title, subtitle = shelf.subtitle, items = indexed) { (i, item) ->
                    val type = item.type()
                    val motion = rememberCoverMotion(
                        covers,
                        key = "$coverKey:$i",
                        shelf = coverKey,
                        hasMovingArt = MovingCoverRules.hasMovingArt(isSong = item.videoId != null && item.browseId == null, type = type),
                    )
                    ItemCard(
                        item.title, item.subtitle, item.thumbnailUrl, type,
                        onClick = { onItemClick(item) },
                        modifier = motion.spot,
                        explicit = item.explicit,
                        imageModifier = motion.art,
                        onArtLoaded = motion.onArtLoaded,
                        menu = item.cardMenu(),
                    )
                }
            }
        }
    }
}

/** A shelf of single tracks, laid out four rows deep like YouTube Music's quick picks. */
@Composable
private fun QuickPicks(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit, action: (@Composable () -> Unit)? = null) {
    Column {
        SectionHeader(shelf.title, subtitle = shelf.subtitle, action = action)
        val pages = rememberPagerState(pageCount = { (shelf.items.size + 3) / 4 })
        HorizontalPager(
            state = pages,
            modifier = Modifier.fillMaxWidth().height(64.dp * 4),
            verticalAlignment = Alignment.Top,
            // The next column's covers peek at the edge, so there's clearly more.
            contentPadding = PaddingValues(end = 40.dp),
        ) { page ->
            Column(Modifier.fillMaxWidth()) {
                for (item in shelf.items.drop(page * 4).take(4)) {
                    val song = item.toSong()
                    if (song != null) {
                        // The same row as Recents, so each gets the three-dot menu.
                        SongListItem(
                            song = song,
                            onClick = { onItemClick(item) },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            swipeToQueue = false,
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(64.dp).clickable { onItemClick(item) }.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Artwork(item.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                com.opentune.ui.components.SubtitleLine(item.subtitle, explicit = item.explicit)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MIXES_LINE = "Daily mixes from what you play, new each day"

/** Today's mixes from this phone's listening, each a card of four covers. */
@Composable
internal fun MixesShelf(mixes: List<com.opentune.data.history.DailyMixes.Mix>, actions: SongActions, line: String = MIXES_LINE) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            "Made for you today",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            items(mixes.size, key = { mixes[it].id }) { i ->
                val mix = mixes[i]
                MixCard(mix) { actions.playAll(mix.songs, 0, false, mix.title) }
            }
        }
    }
}

@Composable
private fun MixCard(mix: com.opentune.data.history.DailyMixes.Mix, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Column(Modifier.width(156.dp).clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.size(156.dp).clip(shape)) {
            val covers = mix.covers
            if (covers.size >= 4) {
                Column {
                    for (row in 0..1) Row {
                        for (col in 0..1) Artwork(covers[row * 2 + col], Modifier.size(78.dp), androidx.compose.ui.graphics.RectangleShape)
                    }
                }
            } else {
                Artwork(covers.firstOrNull(), Modifier.size(156.dp), androidx.compose.ui.graphics.RectangleShape)
            }
            // A band of colour at the foot with the mix's name.
            val accent = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.45f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f),
                        ),
                    ),
            )
            Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp).size(width = 28.dp, height = 4.dp).clip(CircleShape).background(accent))
            Text(
                mix.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 2,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 22.dp),
            )
        }
        Text(
            mix.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp, bottom = 2.dp),
        )
    }
}


/** The activity's own lifecycle, apart from the page's (which also stops when another page covers it). */
@Composable
private fun LocalActivityLifecycle(): androidx.lifecycle.Lifecycle? {
    var c: android.content.Context? = androidx.compose.ui.platform.LocalContext.current
    while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
    return (c as? androidx.lifecycle.LifecycleOwner)?.lifecycle
}
