package com.opentune.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.ROW_ART_PX
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.SectionHeader
import com.opentune.ui.components.Shelf
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.rememberLoader
import com.opentune.ui.type
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onItemClick: (ShelfItem) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val loader = rememberLoader("home") { MusicRepository.home() }
    val state by loader.state.collectAsState()
    val refreshing by loader.refreshing.collectAsState()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        TopAppBar(
            title = {
                Column {
                    Text(greeting(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("OpenTune", style = MaterialTheme.typography.headlineSmall)
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
            },
            scrollBehavior = scroll,
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { loader.reload(keepContent = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                is UiState.Loading -> LazyColumn(contentPadding = contentPadding) {
                    items(4) { ShelfPlaceholder() }
                }
                is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                    ErrorState(s.message, onRetry = { loader.reload() })
                }
                is UiState.Success -> if (s.data.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                        MessageState(
                            Icons.Filled.LibraryMusic,
                            "Nothing here yet",
                            message = "YouTube Music didn't send a home feed. Search for something to start listening.",
                        )
                    }
                } else {
                    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(s.data, key = { i, shelf -> "$i:${shelf.title}" }) { _, shelf -> HomeShelfView(shelf, onItemClick) }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeShelfView(shelf: HomeShelf, onItemClick: (ShelfItem) -> Unit) {
    val songsOnly = shelf.items.isNotEmpty() && shelf.items.all { it.videoId != null && it.browseId == null }
    if (songsOnly && shelf.items.size >= 4) {
        QuickPicks(shelf, onItemClick)
    } else {
        Shelf(
            title = shelf.title,
            subtitle = shelf.subtitle,
            items = shelf.items,
        ) { item ->
            ItemCard(item.title, item.subtitle, item.thumbnailUrl, item.type(), onClick = { onItemClick(item) })
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

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night listening"
}
