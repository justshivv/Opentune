package com.opentune.ui.explore

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.saveable.rememberSaveable
import com.opentune.data.ExploreFeed
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.MusicRepository
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.UiState
import com.opentune.ui.components.ErrorState
import com.opentune.ui.components.PageHeader
import com.opentune.ui.components.MessageState
import com.opentune.ui.components.Placeholder
import com.opentune.ui.components.ShelfPlaceholder
import com.opentune.ui.home.HomeShelfView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.artworkAt
import com.opentune.ui.components.Artwork
import com.opentune.ui.components.pressable
import kotlinx.coroutines.sync.withPermit
import com.opentune.ui.rememberLoader
import kotlin.math.absoluteValue

/**
 * Explore as a board, Pinterest-style: artist pills to jump straight to an
 * artist, chips to narrow the board, then moods, genres, new albums and
 * chart playlists in two columns of differently sized cards.
 */
@Composable
fun ExploreScreen(contentPadding: PaddingValues, onMoodClick: (MoodGenre) -> Unit, onItemClick: (ShelfItem) -> Unit) {
    val loader = rememberLoader("explore:canvas") { ExploreFeed.load() }
    val state by loader.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is UiState.Error -> Column(Modifier.fillMaxSize()) {
            PageHeader("Explore")
            Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) { ErrorState(s.message, onRetry = { loader.reload() }) }
        }
        else -> ExploreBoard((s as? UiState.Success)?.data, contentPadding, filter, { filter = it }, onMoodClick, onItemClick)
    }
}

/** The board itself, from a loaded [canvas] (null while it loads). */
@Composable
internal fun ExploreBoard(
    canvas: ExploreFeed.Canvas?,
    contentPadding: PaddingValues,
    filter: String?,
    onFilter: (String?) -> Unit,
    onMoodClick: (MoodGenre) -> Unit,
    onItemClick: (ShelfItem) -> Unit,
) {
    val tiles = remember(canvas, filter) { canvas?.tiles?.filter { filter == null || it.kind.name == filter }.orEmpty() }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = StaggeredGridItemSpan.FullLine, key = "header") { PageHeader("Explore") }
        if (canvas == null) {
            item(span = StaggeredGridItemSpan.FullLine, key = "pillsLoading") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    repeat(4) { Placeholder(Modifier.width(110.dp).height(44.dp), RoundedCornerShape(22.dp)) }
                }
            }
            items(10, key = { "ph$it" }) { i ->
                Placeholder(Modifier.fillMaxWidth().height(tileHeights[i % tileHeights.size]), RoundedCornerShape(22.dp))
            }
            return@LazyVerticalStaggeredGrid
        }
        if (canvas.artists.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine, key = "artists") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(canvas.artists, key = { it.browseId }) { a ->
                        ArtistPill(a) { onItemClick(ShelfItem(a.name, "", a.thumbnailUrl, null, a.browseId)) }
                    }
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine, key = "filters") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterPill("For you", filter == null) { onFilter(null) } }
                items(ExploreFeed.Kind.entries.filter { k -> canvas.tiles.any { it.kind == k } }) { k ->
                    FilterPill(k.label, filter == k.name) { onFilter(if (filter == k.name) null else k.name) }
                }
            }
        }
        if (tiles.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                MessageState(Icons.Filled.Explore, "Nothing to explore", message = "YouTube Music didn't send anything for this.")
            }
        }
        items(tiles, key = { it.key }) { tile ->
            when (tile) {
                is ExploreFeed.Tile.Mood -> MoodTile(tile.mood, height = heightFor(tile.key), onClick = { onMoodClick(tile.mood) })
                is ExploreFeed.Tile.Item -> BoardCard(tile, onClick = { onItemClick(tile.item) })
            }
        }
    }
}

private val tileHeights = listOf(150.dp, 210.dp, 180.dp, 250.dp, 130.dp, 200.dp)
private val cardAspects = listOf(1f, 0.8f, 1.2f, 0.9f)

/** A tile's height, fixed by its key so the board doesn't reshuffle on every visit. */
private fun heightFor(key: String) = tileHeights[key.hashCode().absoluteValue % tileHeights.size]
private fun aspectFor(key: String) = cardAspects[key.hashCode().absoluteValue % cardAspects.size]

/** An artist to jump to: round photo (or initial) and name, in a pill. */
@Composable
private fun ArtistPill(artist: ExploreFeed.Artist, onClick: () -> Unit) {
    Row(
        Modifier
            .height(46.dp)
            .pressable(onClick)
            .clip(RoundedCornerShape(23.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 5.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                artist.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (artist.thumbnailUrl != null) {
                Artwork(artist.thumbnailUrl.artworkAt(120), Modifier.matchParentSize(), CircleShape, placeholder = Icons.Filled.Person)
            }
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).widthIn(max = 160.dp),
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer, label = "pillBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, label = "pillFg")
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/**
 * An album or playlist pin: the cover at its own height, rounded, with the
 * title and where it's from underneath.
 */
@Composable
private fun BoardCard(tile: ExploreFeed.Tile.Item, onClick: () -> Unit) {
    val item = tile.item
    Column(Modifier.fillMaxWidth().pressable(onClick, pressedScale = 0.97f)) {
        Box {
            Artwork(
                item.thumbnailUrl.artworkAt(CARD_ART_PX),
                Modifier.fillMaxWidth().aspectRatio(aspectFor(tile.key)),
                RoundedCornerShape(22.dp),
            )
            Text(
                tile.kind.label.removeSuffix("s"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
        val sub = item.subtitle.ifBlank { tile.from }
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Covers for mood tiles, fetched lazily (the first playlist on each mood's
 * page), a few at a time, and kept for the session.
 */
private object MoodCovers {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val gate = kotlinx.coroutines.sync.Semaphore(3)

    fun cached(mood: MoodGenre): String? = mood.thumbnailUrl ?: cache[mood.browseId + mood.params]

    suspend fun load(mood: MoodGenre): String? {
        cached(mood)?.let { return it }
        return gate.withPermit {
            runCatching {
                MusicRepository.shelves(mood.browseId, mood.params)
                    .firstNotNullOfOrNull { shelf -> shelf.items.firstNotNullOfOrNull { it.thumbnailUrl } }
            }.getOrNull()?.also { cache[mood.browseId + mood.params] = it }
        }
    }
}

/**
 * A mood card: the first cover from the mood's playlists fills it behind a
 * scrim in the mood's own colour, with the name set low on the left. Until
 * the cover arrives, the colour alone carries it. The hue comes from the
 * title, so each mood keeps its colour.
 */
@Composable
private fun MoodTile(mood: MoodGenre, height: androidx.compose.ui.unit.Dp = 104.dp, onClick: () -> Unit) {
    val hue = (mood.title.hashCode().absoluteValue % 360).toFloat()
    val colour = Color.hsv(hue, 0.65f, 0.55f)
    var cover by remember(mood) { mutableStateOf(MoodCovers.cached(mood)) }
    LaunchedEffect(mood) { if (cover == null) cover = MoodCovers.load(mood) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pressable(onClick, pressedScale = 0.97f)
            .clip(RoundedCornerShape(22.dp))
            .background(colour),
    ) {
        AnimatedVisibility(cover != null, enter = fadeIn(tween(500)), modifier = Modifier.matchParentSize()) {
            Artwork(cover.artworkAt(CARD_ART_PX), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(colour.copy(alpha = 0.05f), colour.copy(alpha = 0.55f), colour.copy(alpha = 0.95f))),
            ),
        )
        Text(
            mood.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp, end = 14.dp),
        )
    }
}

/** A mood or genre's own page: shelves of playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(
    title: String,
    browseId: String,
    params: String?,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
) {
    val loader = rememberLoader("mood:$browseId:$params") { MusicRepository.shelves(browseId, params) }
    val state by loader.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, onBack = onBack)
        when (val s = state) {
            is UiState.Loading -> LazyColumn { items(3) { ShelfPlaceholder() } }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                ErrorState(s.message, onRetry = { loader.reload() })
            }
            is UiState.Success -> LazyColumn(contentPadding = contentPadding) {
                items(s.data) { HomeShelfView(it, onItemClick) }
            }
        }
    }
}
