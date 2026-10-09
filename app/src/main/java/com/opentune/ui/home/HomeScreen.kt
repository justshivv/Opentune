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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
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
import com.opentune.ui.type

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    actions: SongActions,
    onItemClick: (ShelfItem) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the page that names a song from what the microphone hears. */
    onRecognize: (() -> Unit)? = null,
) {
    val loader = rememberLoader("home") { MusicRepository.home() }
    val state by loader.state.collectAsState()
    val refreshing by loader.refreshing.collectAsState()
    val moreAll by MusicRepository.homeMore.collectAsState()
    val library by AppSettings.library.collectAsState()
    val hideExplicit = library.hideExplicit
    val more = remember(moreAll, hideExplicit) { ContentFilter.shelves(moreAll, hideExplicit) }
    val records by History.records.collectAsState()
    val recents = remember(records) { History.recents(records, 24) }
    val mixes = remember(records) { com.opentune.data.history.DailyMixes.build(records) }
    val ui by AppSettings.ui.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val account by AccountStore.account.collectAsState()
    // Signing in or out changes what YouTube Music recommends; fetch again then.
    var seenSignedIn by remember { mutableStateOf(signedIn) }
    LaunchedEffect(signedIn) {
        if (signedIn != seenSignedIn) {
            seenSignedIn = signedIn
            loader.reload(keepContent = true)
        }
    }

    com.opentune.ui.components.MarkRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { loader.reload(keepContent = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
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
                val firstName = account?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(firstName ?: "What's playing?", style = MaterialTheme.typography.displaySmall)
                    }
                    if (onRecognize != null) {
                        GlassIconButton(Icons.Rounded.GraphicEq, "Name a song playing nearby", onRecognize, size = 52.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    GlassIconButton(Icons.Rounded.Person, "Account and settings", onOpenSettings, size = 52.dp) {
                        val photo = account?.thumbnailUrl
                        if (photo != null) Artwork(photo, Modifier.size(44.dp), CircleShape)
                        else Icon(Icons.Rounded.Person, "Account and settings", Modifier.size(26.dp))
                    }
                }
            }
            if (recents.isNotEmpty()) {
                item(key = "recents") {
                    Recents(
                        songs = recents,
                        asGrid = ui.recentsAsGrid,
                        actions = actions,
                        onToggleLayout = { AppSettings.updateUi { it.copy(recentsAsGrid = !it.recentsAsGrid) } },
                    )
                }
            }
            if (mixes.isNotEmpty()) {
                item(key = "mixes") { MixesShelf(mixes, actions) }
            }
            when (val s = state) {
                is UiState.Loading -> items(3) { ShelfPlaceholder() }
                is UiState.Error -> item(key = "error") {
                    ErrorState(s.message, onRetry = { loader.reload() }, modifier = Modifier.padding(top = 24.dp))
                }
                is UiState.Success -> {
                    val first = ContentFilter.shelves(s.data, hideExplicit)
                    itemsIndexed(first, key = { i, shelf -> "$i:${shelf.title}" }, contentType = { _, shelf -> shelfType(shelf) }) { _, shelf ->
                        HomeShelfView(shelf, onItemClick)
                    }
                    // The rest of Home, which arrives a page at a time after the first.
                    itemsIndexed(more, key = { i, shelf -> "more$i:${shelf.title}" }, contentType = { _, shelf -> shelfType(shelf) }) { _, shelf ->
                        HomeShelfView(shelf, onItemClick, Modifier.animateItem())
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
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    itemsIndexed(songs) { i, song ->
                        ItemCard(song.title, song.artist, song.thumbnailUrl, null, onClick = { actions.playAll(songs, i, false, "Recents") })
                    }
                }
            } else {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(64.dp * 4),
                ) {
                    items(songs.size) { i ->
                        val song = songs[i]
                        SongListItem(
                            song = song,
                            onClick = { actions.playAll(songs, i, false, "Recents") },
                            modifier = Modifier.width(340.dp),
                            isCurrent = song.videoId == actions.currentVideoId,
                            isPlaying = actions.isPlaying,
                            onPlayNext = { actions.playNext(song) },
                            onAddToQueue = { actions.addToQueue(song) },
                            // A sideways drag here scrolls the grid; Play next and
                            // Add to queue stay in the long-press menu.
                            swipeToQueue = false,
                        )
                    }
                }
            }
        }
    }
}

/** Songs-only shelves of four or more are laid out as quick picks. */
private fun HomeShelf.isQuickPicks(): Boolean =
    items.size >= 4 && items.all { it.videoId != null && it.browseId == null }

/** Lets the list reuse a shelf scrolled away for the next one of the same kind. */
private fun shelfType(shelf: HomeShelf): String = if (shelf.isQuickPicks()) "picks" else "shelf"

@Composable
fun HomeShelfView(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (shelf.isQuickPicks()) {
            QuickPicks(shelf, onItemClick)
        } else {
            Shelf(title = shelf.title, subtitle = shelf.subtitle, items = shelf.items) { item ->
                ItemCard(item.title, item.subtitle, item.thumbnailUrl, item.type(), onClick = { onItemClick(item) })
            }
        }
    }
}

/** A shelf of single tracks, laid out four rows deep like YouTube Music's quick picks. */
@Composable
private fun QuickPicks(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit) {
    Column {
        SectionHeader(shelf.title, subtitle = shelf.subtitle)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(64.dp * 4),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(shelf.items) { item ->
                Row(
                    Modifier
                        .width(300.dp)
                        .height(64.dp)
                        .clickable { onItemClick(item) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Artwork(item.thumbnailUrl.artworkAt(ROW_ART_PX), Modifier.size(48.dp))
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
                }
            }
        }
    }
}

/** Today's mixes from this phone's listening, each a card of four covers. */
@Composable
internal fun MixesShelf(mixes: List<com.opentune.data.history.DailyMixes.Mix>, actions: SongActions) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            "Made for you today",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Daily mixes from what you play, new each day",
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
