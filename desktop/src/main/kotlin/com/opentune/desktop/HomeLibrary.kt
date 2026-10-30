package com.opentune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.history.DailyMixes
import com.opentune.data.history.PlayRecord
import com.opentune.data.history.SpeedDial
import com.opentune.data.history.Wrapped
import com.opentune.data.model.MoodGenre
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A mood or genre opened from Home's chips. */
internal data class MoodKey(val title: String, val browseId: String, val params: String?)

/** Every play on this computer, newest first, read again when a new one is recorded. */
@Composable
private fun rememberHistory(): List<PlayRecord> {
    val recent by Library.recent.collectAsState()
    val history by produceState(emptyList<PlayRecord>(), recent) {
        value = withContext(Dispatchers.IO) { Library.history() }
    }
    return history
}

// ---- Home --------------------------------------------------------------------

/**
 * Home, filled as the phone fills it: moods and genres to start from, your
 * speed dial and daily mixes made from what you play here, then YouTube
 * Music's rows (quick picks as a grid of songs), page after page as you
 * scroll, carrying on with new releases and the charts when they run out.
 */
@Composable
internal fun HomePage(onOpen: (ShelfItem) -> Unit, onOpenAny: (Any) -> Unit) {
    var shelves by remember { mutableStateOf<List<Pages.Shelf>?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var extrasDone by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val recent by Library.recent.collectAsState()
    val liked by Library.liked.collectAsState()
    val history = rememberHistory()
    val moods by produceState(emptyList<MoodGenre>()) { value = runCatching { Pages.moods() }.getOrDefault(emptyList()) }
    fun load() {
        failed = false
        scope.launch {
            runCatching { Pages.home() }
                .onSuccess { shelves = it.shelves; next = it.next }
                .onFailure { failed = true }
        }
    }
    fun add(more: List<Pages.Shelf>) {
        // A row sent twice is shown once.
        val have = shelves.orEmpty().mapTo(HashSet()) { it.title.lowercase() }
        shelves = shelves.orEmpty() + more.filter { it.title.lowercase() !in have }
    }
    fun more() {
        if (loadingMore || shelves == null) return
        val token = next
        if (token == null && extrasDone) return
        loadingMore = true
        scope.launch {
            if (token != null) {
                runCatching { Pages.homeMore(token) }.onSuccess { add(it.shelves); next = it.next }.onFailure { next = null }
            } else {
                add(Pages.moreForHome())
                extrasDone = true
            }
            loadingMore = false
        }
    }
    LaunchedEffect(Unit) { load() }
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, next, shelves, extrasDone) { if (nearEnd) more() }

    val speedDial = remember(history) { SpeedDial.fromHistory(history, limit = 27) }
    val mixes = remember(history) { DailyMixes.build(history) }
    val loaded = shelves
    LazyColumn(state = list, contentPadding = PaddingValues(top = 24.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item(key = "title") {
            Column {
                PageTitle("Home")
                Text(greeting(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        if (mixes.isNotEmpty() || speedDial.isNotEmpty()) item(key = "picks") {
            Section("Top picks for you") { TopPicks(mixes, speedDial.take(8), onOpenMix = { onOpenAny(it) }) }
        }
        if (recent.isNotEmpty()) item(key = "recents") { SongGrid("Recents", recent.take(32)) }
        if (moods.isNotEmpty()) item(key = "moods") {
            Section("Find your mood") { MoodTiles(moods) { onOpenAny(MoodKey(it.title, it.browseId, it.params)) } }
        }
        when {
            failed -> item(key = "failed") { Message("Couldn't load Home", "Check your connection and try again.", action = "Try again" to ::load) }
            loaded == null -> items(3, key = { "placeholder$it" }) { ShelfPlaceholder() }
            else -> {
                itemsIndexed(loaded, key = { i, _ -> "yt$i" }) { _, shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf)) else ShelfRow(shelf, onOpen)
                }
                if (next != null || !extrasDone) item(key = "more") { ShelfPlaceholder() }
            }
        }
        if (liked.isNotEmpty() && loaded != null) item(key = "liked") {
            ShelfRow(Pages.Shelf("From your liked songs", liked.take(20).map(Pages::cardOf)), onOpen)
        }
    }
}

/** A row of songs (quick picks, trending): shown as columns of song rows, as BitChord's desktop shows them, not as cards. */
private fun isSongShelf(shelf: Pages.Shelf) =
    shelf.title.trim().equals("Quick picks", ignoreCase = true) || shelf.items.size >= 8 && shelf.items.count { it.videoId != null } * 10 >= shelf.items.size * 9

/** Good morning, afternoon or evening, as Home on the phone greets you. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** A titled part of a page, with its grey line under the title. */
@Composable
private fun Section(title: String, subtitle: String? = null, onShowAll: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title, subtitle, onShowAll)
        content()
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null, onShowAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onShowAll != null) {
            Text(
                "Show all", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onShowAll).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** One of Home's rows: its title, and its cards in a row that scrolls sideways. */
@Composable
internal fun ShelfRow(shelf: Pages.Shelf, onOpen: (ShelfItem) -> Unit) {
    Section(shelf.title) {
        val songs = remember(shelf) { shelf.items.mapNotNull(Pages::songOf) }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(shelf.items) { item ->
                Card(item) {
                    val song = Pages.songOf(item)
                    if (song != null) Player.playAll(songs, songs.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)) else onOpen(item)
                }
            }
        }
    }
}

/** A shelf's shape, shimmering, while Home loads. */
@Composable
internal fun ShelfPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Placeholder(Modifier.padding(horizontal = 24.dp).width(180.dp).height(22.dp), RoundedCornerShape(8.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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

/** Moods and genres as a row of pills. */
@Composable
private fun MoodChips(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(moods, key = { it.title }) { m ->
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val onSurface = MaterialTheme.colorScheme.onSurface
            val fill by animateColorAsState(onSurface.copy(alpha = if (hovered) 0.16f else 0.08f), tween(140), label = "chip")
            Text(
                m.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).hoverable(hover)
                    .clickable(interactionSource = hover, indication = null) { onOpen(m) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Speed dial: three rows of covers that scroll sideways, the name on each,
 * as the phone's Home starts. Click one to play it, with the rest after it.
 */
@Composable
private fun SpeedDialGrid(items: List<ShelfItem>) {
    val songs = remember(items) { items.mapNotNull(Pages::songOf) }
    val side = 132.dp
    LazyHorizontalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxWidth().height(side * 3 + 20.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items) { item ->
            val hover = remember { MutableInteractionSource() }
            Box(
                Modifier.size(side).hoverLift(hover, 1.04f).clip(RoundedCornerShape(14.dp))
                    .clickable(interactionSource = hover, indication = null) {
                        val at = songs.indexOfFirst { it.videoId == item.videoId }
                        if (at >= 0) Player.playAll(songs, at)
                    },
            ) {
                Cover(item.thumbnailUrl, side, RoundedCornerShape(14.dp))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
                Text(
                    item.title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
            }
        }
    }
}

/** Songs as four rows that scroll sideways in columns, each with its cover, title and artist. */
@Composable
internal fun SongGrid(title: String, songs: List<Song>, rows: Int = 4) {
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val playingId = queue.getOrNull(index)?.videoId
    Section(title) {
        LazyHorizontalGrid(
            GridCells.Fixed(rows.coerceAtMost(songs.size).coerceAtLeast(1)),
            Modifier.fillMaxWidth().height(64.dp * rows.coerceAtMost(songs.size).coerceAtLeast(1) - 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(songs) { song ->
                val hover = remember { MutableInteractionSource() }
                Row(
                    Modifier.width(320.dp).height(60.dp).hoverWash(hover, RoundedCornerShape(10.dp))
                        .clickable(interactionSource = hover, indication = null) { Player.playAll(songs, songs.indexOf(song)) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(song.thumbnailUrl, 48.dp, RoundedCornerShape(8.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (song.videoId == playingId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Top picks: tall cards, your daily mixes as a burst of colour with three
 * of their covers in circles, then the songs you play most as full covers.
 */
@Composable
private fun TopPicks(mixes: List<DailyMixes.Mix>, most: List<ShelfItem>, onOpenMix: (DailyMixes.Mix) -> Unit) {
    val songs = remember(most) { most.mapNotNull(Pages::songOf) }
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(mixes, key = { "mix" + it.id }) { mix -> PickCard(label = "Made for you", title = mix.title, line = mix.subtitle, colourOf = mix.covers.firstOrNull(), onClick = { onOpenMix(mix) }) {
            MixCircles(mix.covers.filterNotNull())
        } }
        items(most, key = { "most" + (it.videoId ?: it.title) }) { item ->
            PickCard(label = "Played most", title = item.title, line = item.subtitle, colourOf = item.thumbnailUrl, fullCover = item.thumbnailUrl, onClick = {
                val at = songs.indexOfFirst { it.videoId == item.videoId }
                if (at >= 0) Player.playAll(songs, at)
            })
        }
    }
}

/** A tall card: a cover filling it, or a colour from [colourOf] with [art] on it; its kind, title and a line at the foot. */
@Composable
private fun PickCard(label: String, title: String, line: String, colourOf: String?, fullCover: String? = null, onClick: () -> Unit, art: @Composable () -> Unit = {}) {
    val hover = remember { MutableInteractionSource() }
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val colour = rememberCoverColor(colourOf, base)
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.width(196.dp).height(262.dp).hoverLift(hover, 1.03f).clip(shape)
            .background(Brush.linearGradient(listOf(lerpColor(colour, Color.White, 0.12f), lerpColor(colour, Color.Black, 0.35f))))
            .clickable(interactionSource = hover, indication = null, onClick = onClick),
    ) {
        if (fullCover != null) Cover(fullCover, 262.dp, shape, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { art() }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Three covers in overlapping circles, as a mix's card shows who's in it. */
@Composable
private fun MixCircles(covers: List<String>) {
    Box(Modifier.size(170.dp).padding(bottom = 40.dp)) {
        covers.getOrNull(0)?.let { Cover(it, 104.dp, CircleShape, Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp)) }
        covers.getOrNull(1)?.let { Cover(it, 70.dp, CircleShape, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) }
        covers.getOrNull(2)?.let { Cover(it, 58.dp, CircleShape, Modifier.align(Alignment.BottomCenter)) }
    }
}

/** Moods and genres as coloured tiles, two rows that scroll sideways. */
@Composable
private fun MoodTiles(moods: List<MoodGenre>, onOpen: (MoodGenre) -> Unit) {
    val palette = listOf(0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828, 0xFFEF6C00, 0xFF00838F, 0xFFAD1457, 0xFF4E342E, 0xFF283593, 0xFF9E9D24)
    LazyHorizontalGrid(
        GridCells.Fixed(2), Modifier.fillMaxWidth().height(176.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(moods, key = { it.title }) { m ->
            val hover = remember { MutableInteractionSource() }
            val colour = Color(palette[(m.title.hashCode() and 0x7fffffff) % palette.size])
            Box(
                Modifier.width(200.dp).height(82.dp).hoverLift(hover, 1.03f).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(colour, lerpColor(colour, Color.Black, 0.35f))))
                    .clickable(interactionSource = hover, indication = null) { onOpen(m) }.padding(14.dp),
            ) { Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2) }
        }
    }
}

/**
 * New: what's just come out and what's climbing, as BitChord's New tab has
 * it: new albums and singles as covers, trending songs in columns of rows,
 * then the charts.
 */
@Composable
internal fun NewPage(onOpen: (ShelfItem) -> Unit) {
    val shelves by produceState<List<Pages.Shelf>?>(null) { value = runCatching { Pages.moreForHome() }.getOrDefault(emptyList()) }
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item { PageTitle("New") }
        when (val loaded = shelves) {
            null -> items(3) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) item { Message("Nothing new could be loaded", "Check your connection and try again.") }
                else items(loaded) { shelf ->
                    if (isSongShelf(shelf)) SongGrid(shelf.title, shelf.items.mapNotNull(Pages::songOf)) else ShelfRow(shelf, onOpen)
                }
        }
    }
}

/** A daily mix: four of its covers, its name and who's in it. */
@Composable
private fun MixCard(mix: DailyMixes.Mix, onOpen: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.width(180.dp).hoverLift(hover).clip(MaterialTheme.shapes.medium).clickable(interactionSource = hover, indication = null, onClick = onOpen)) {
        Box {
            Mosaic(mix.covers.filterNotNull(), Modifier.size(180.dp))
            androidx.compose.animation.AnimatedVisibility(
                hovered, enter = androidx.compose.animation.fadeIn(Motion.fade(160)), exit = androidx.compose.animation.fadeOut(Motion.fade(120)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable { Player.playAll(mix.songs, 0) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play ${mix.title}", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp)) }
            }
        }
        Text(mix.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A mood or genre's own page: its rows of playlists. */
@Composable
internal fun MoodPage(mood: MoodKey, onOpen: (ShelfItem) -> Unit) {
    val shelves by produceState<List<Pages.Shelf>?>(null, mood) {
        value = runCatching { Pages.shelves(mood.browseId, mood.params) }.getOrDefault(emptyList())
    }
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item { PageTitle(mood.title) }
        when (val loaded = shelves) {
            null -> items(3) { ShelfPlaceholder() }
            else -> if (loaded.isEmpty()) item { Message("Nothing here right now", "Try another mood or genre.") }
                else items(loaded) { ShelfRow(it, onOpen) }
        }
    }
}

/** A daily mix's songs, with play and shuffle. */
@Composable
internal fun MixPage(mix: DailyMixes.Mix) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mosaic(mix.covers.filterNotNull(), Modifier.size(150.dp))
            Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mix.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(mix.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play", Icons.Rounded.PlayArrow) { Player.playAll(mix.songs, 0) }
                }
            }
        }
        SongList(mix.songs) { s, i -> Player.playAll(s, i) }
    }
}

// ---- Library -----------------------------------------------------------------

/**
 * The Library, as the phone opens it: your year so far, shortcuts to liked
 * songs, what you played and your server, your playlists as tiles, what you
 * play most this month (songs and artists), and the last songs you played.
 */
@Composable
internal fun LibraryHome(onShelf: (Shelf) -> Unit, onPlaylist: (String) -> Unit, onOpenAny: (Any) -> Unit) {
    val liked by Library.liked.collectAsState()
    val recent by Library.recent.collectAsState()
    val playlists by Library.playlists.collectAsState()
    val server by Subsonic.server.collectAsState()
    val history = rememberHistory()
    var naming by remember { mutableStateOf(false) }
    val now = remember(history) { System.currentTimeMillis() }
    val year = remember(history) {
        val start = java.time.LocalDate.now().withDayOfYear(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Wrapped.summarize(history, start, now)
    }
    val month = remember(history) { Wrapped.summarize(history, now - 30L * 86_400_000L, now) }
    val mixes = remember(history) { DailyMixes.build(history) }
    LazyColumn(contentPadding = PaddingValues(top = 24.dp, bottom = LocalPageBottom.current), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item(key = "title") { PageTitle("Library") }
        item(key = "year") { YearCard(year) }
        item(key = "shortcuts") {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Shortcut(Icons.Rounded.Favorite, "Liked songs", songCount(liked.size), heart = true) { onShelf(Shelf.LIKED) } }
                item { Shortcut(Icons.Rounded.History, "Recently played", if (recent.isEmpty()) "Nothing yet" else "${recent.size} songs") { onShelf(Shelf.RECENT) } }
                if (server != null) item { Shortcut(Icons.Rounded.Storage, "Your server", server!!.url.substringAfter("://").substringBefore('/')) { onShelf(Shelf.SERVER) } }
                item { Shortcut(Icons.Rounded.Add, "New playlist", "Start one here") { naming = true } }
            }
        }
        item(key = "playlists") {
            Section("Playlists", if (playlists.isEmpty()) "Make one with New playlist" else songCountOf(playlists.size, "playlist"), onShowAll = { onShelf(Shelf.PLAYLISTS) }.takeIf { playlists.isNotEmpty() }) {
                if (playlists.isEmpty()) {
                    Text(
                        "Playlists you make show here. Add songs to one with the playlist button on any song.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            val hover = remember { MutableInteractionSource() }
                            Column(Modifier.width(170.dp).hoverLift(hover).clip(MaterialTheme.shapes.medium).clickable(interactionSource = hover, indication = null) { onPlaylist(p.id) }) {
                                Mosaic(p.covers, Modifier.size(170.dp))
                                Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                Text(songCount(p.songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (mixes.isNotEmpty()) item(key = "mixes") {
            Section("Your mixes", "Made fresh each day from what you play") {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(mixes, key = { it.id }) { mix -> MixCard(mix, onOpen = { onOpenAny(mix) }) }
                }
            }
        }
        if (month.topSongs.isNotEmpty()) item(key = "top-songs") {
            Section("On repeat this month", "${month.plays} plays, ${month.minutes} minutes") {
                val songs = month.topSongs.mapNotNull { it.song }.take(10)
                Column(Modifier.padding(horizontal = 16.dp)) {
                    month.topSongs.take(5).forEachIndexed { i, e ->
                        RankRow(i + 1, e) { e.song?.let { s -> Player.playAll(songs, songs.indexOfFirst { it.videoId == s.videoId }.coerceAtLeast(0)) } }
                    }
                }
            }
        }
        if (month.topArtists.isNotEmpty()) item(key = "top-artists") {
            Section("Your top artists", "This month") {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(month.topArtists.take(12), key = { it.title }) { a ->
                        Column(Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).clickable { a.song?.let { Player.playAll(listOf(it), 0) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(a.thumbnailUrl, 120.dp, CircleShape)
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text("${a.plays} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) item(key = "recent") {
            Section("Recently played", onShowAll = { onShelf(Shelf.RECENT) }) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    recent.take(6).forEachIndexed { i, s -> SongLine(s) { Player.playAll(recent, i) } }
                }
            }
        }
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            onPlaylist(Library.createPlaylist(name))
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"
private fun songCountOf(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

/**
 * Your year so far, as the phone's Library card shows it: minutes, plays,
 * songs and artists, with the song you've played most and its cover.
 */
@Composable
private fun YearCard(summary: Wrapped.Summary) {
    val top = summary.topSongs.firstOrNull()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val colour = rememberCoverColor(top?.thumbnailUrl, base)
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(tint(colour, base, 0.55f), tint(colour, base, 0.18f)))),
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your ${java.time.Year.now().value} so far", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
                if (summary.isEmpty) {
                    Text("Play some music and your year shows here", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                } else {
                    Text("${"%,d".format(summary.minutes)} minutes", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Stat("${summary.plays}", "plays")
                        Stat("${summary.songs}", "songs")
                        Stat("${summary.artists}", "artists")
                    }
                    summary.topArtists.firstOrNull()?.let {
                        Text("Top artist: ${it.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (top != null) {
                Column(Modifier.padding(start = 20.dp).width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { top.song?.let { Player.playAll(listOf(it), 0) } }) {
                        Cover(top.thumbnailUrl, 150.dp, RoundedCornerShape(14.dp))
                    }
                    Text(top.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text("Played ${top.plays} times", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
}

/** A wide tile leading into part of the Library. */
@Composable
private fun Shortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, heart: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(onSurface.copy(alpha = if (hovered) 0.12f else 0.07f), tween(140), label = "shortcut")
    Row(
        Modifier.width(250.dp).height(76.dp).clip(RoundedCornerShape(16.dp)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(
                if (heart) Brush.linearGradient(listOf(Color(0xFF7B5CFF), Color(0xFFE0E0FF))) else Brush.linearGradient(listOf(onSurface.copy(alpha = 0.14f), onSurface.copy(alpha = 0.08f))),
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (heart) Color.White else onSurface, modifier = Modifier.size(24.dp)) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A numbered row in a top list. */
@Composable
private fun RankRow(rank: Int, entry: Wrapped.Entry, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp)).clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
        Cover(entry.thumbnailUrl, 48.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${entry.plays} plays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A song in a short list on a page that isn't only songs. */
@Composable
private fun SongLine(song: Song, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().hoverWash(hover, RoundedCornerShape(12.dp)).clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.thumbnailUrl, 48.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
