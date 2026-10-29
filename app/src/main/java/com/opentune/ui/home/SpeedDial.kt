package com.opentune.ui.home

import com.opentune.ui.components.menuClickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.history.SpeedDial
import com.opentune.data.model.BrowseType
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.artworkAt
import com.opentune.ui.cardMenu
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.CardMenu
import com.opentune.ui.components.CardPreview
import com.opentune.ui.components.CollectionContextMenu
import com.opentune.ui.components.ItemCard
import com.opentune.ui.components.MenuAnchor
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.SongContextMenu
import com.opentune.ui.components.menuAnchor
import com.opentune.ui.components.rememberHaptics
import com.opentune.ui.type

/** What Home's Speed dial shows, for its full page: Home sets it as it lays out. */
object SpeedDialItems {
    var items by mutableStateOf<List<ShelfItem>>(emptyList())
}

/**
 * Speed dial, as in YouTube Music: the songs, albums, playlists and artists
 * played most, three rows of three to a page, a swipe apart. The header
 * opens them all on a page of their own.
 */
@Composable
internal fun SpeedDialSection(
    items: List<ShelfItem>,
    /** The grey line over the title: YouTube Music's (the account's name), or the first name. */
    strapline: String,
    avatarUrl: String?,
    onItemClick: (ShelfItem) -> Unit,
    onOpenAll: () -> Unit,
    title: String = SpeedDial.TITLE,
) {
    val shown = items.take(SpeedDial.ON_HOME)
    if (shown.isEmpty()) return
    val pageCount = (shown.size + PER_PAGE - 1) / PER_PAGE
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Show all", onClick = onOpenAll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(avatarUrl, Modifier.size(48.dp), CircleShape, placeholder = Icons.Rounded.Person)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (strapline.isNotBlank()) {
                    Text(strapline.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Show all", Modifier.size(28.dp))
        }
        val pager = rememberPagerState(pageCount = { pageCount })
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val tile = (maxWidth - SIDE * 2 - GAP * 2) / 3
            // Reserve only occupied rows. A short page must not leave two blank rows
            // between Speed dial and Quick picks; ease the height when paging.
            val visibleCount = (shown.size - pager.currentPage * PER_PAGE).coerceIn(1, PER_PAGE)
            val rows = (visibleCount + 2) / 3
            val pageHeight by animateDpAsState(tile * rows + GAP * (rows - 1), label = "speed-dial-height")
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().height(pageHeight),
                contentPadding = PaddingValues(horizontal = SIDE),
                pageSpacing = SIDE,
                verticalAlignment = Alignment.Top,
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    val pageItems = (shown.size - page * PER_PAGE).coerceIn(0, PER_PAGE)
                    for (row in 0 until (pageItems + 2) / 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            for (column in 0 until 3) {
                                val item = shown.getOrNull(page * PER_PAGE + row * 3 + column)
                                if (item != null) SpeedDialTile(item, { onItemClick(item) }, Modifier.size(tile))
                                else Spacer(Modifier.size(tile))
                            }
                        }
                    }
                }
            }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pageCount) { i ->
                    val on = pager.currentPage == i
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (on) 0.9f else 0.3f)),
                    )
                }
            }
        }
    }
}

/** One square: the cover edge to edge (an artist's photo round), its name over a shade at the foot. */
@Composable
private fun SpeedDialTile(item: ShelfItem, onClick: () -> Unit, modifier: Modifier) {
    val round = item.type() == BrowseType.ARTIST
    val menu = item.cardMenu()
    val anchor = remember { MenuAnchor() }
    val view = LocalView.current
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    val at = anchor.rect
    if (menuOpen && at != null && menu != null) {
        val preview: @Composable () -> Unit = { CardPreview(item.title, item.subtitle, item.thumbnailUrl, round) }
        when (menu) {
            is CardMenu.ForSong -> SongContextMenu(menu.song, at, onDismiss = { menuOpen = false }, preview = preview)
            is CardMenu.ForList -> CollectionContextMenu(menu.list, at, onDismiss = { menuOpen = false }, preview = preview)
        }
    }
    Box(
        modifier
            .menuAnchor(anchor, view)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (menu == null) Modifier.clickable(onClick = onClick)
                else Modifier.menuClickable(onClick = onClick, onLongClick = { haptics.press(); menuOpen = true }),
            ),
    ) {
        if (round) {
            Artwork(
                item.thumbnailUrl.artworkAt(CARD_ART_PX),
                Modifier.fillMaxSize(0.86f).align(Alignment.TopCenter).padding(top = 8.dp).aspectRatio(1f),
                CircleShape,
                placeholder = Icons.Rounded.Person,
            )
        } else {
            Artwork(item.thumbnailUrl.artworkAt(CARD_ART_PX), Modifier.fillMaxSize(), RectangleShape)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // An album, playlist or artist opens a page rather than playing.
            if (item.browseId != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Color.White)
        }
    }
}

/** Every Speed dial pick, four to a row, each with its kind and artist under it. */
@Composable
fun SpeedDialScreen(contentPadding: PaddingValues, onItemClick: (ShelfItem) -> Unit, onBack: () -> Unit) {
    // Opened again after Android closed the app in the background, Home hasn't set them
    // yet: then the picks made on the phone, rather than an empty page.
    val records by com.opentune.data.history.History.records.collectAsState()
    val plays by com.opentune.data.history.ListenedCollections.plays.collectAsState()
    val local = remember(records, plays) { SpeedDial.fromHistory(records, collections = plays) }
    val items = SpeedDial.personalized(local, SpeedDialItems.items)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = (maxWidth - 16.dp) / 4
        LazyVerticalGrid(GridCells.Fixed(4), contentPadding = contentPadding, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { PageHeader(com.opentune.ui.theme.tr(SpeedDial.TITLE), onBack = onBack) }
            itemsIndexed(items, key = { i, item -> "$i:${item.videoId ?: item.browseId}" }) { _, item ->
                ItemCard(
                    item.title, item.subtitle, item.thumbnailUrl, item.type(),
                    onClick = { onItemClick(item) },
                    width = width,
                    explicit = item.explicit,
                    menu = item.cardMenu(),
                )
            }
        }
    }
}

private const val PER_PAGE = 9
private val SIDE = 16.dp
private val GAP = 8.dp
