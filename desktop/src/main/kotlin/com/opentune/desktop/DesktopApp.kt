package com.opentune.desktop

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
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pages, in the sidebar and (all but Settings) the dock; search sits apart in its own circle. */
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val inDock: Boolean = true) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    QUEUE("Up next", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings, inDock = false),
}

private val DOCK_HEIGHT = 64.dp
private val MINI_HEIGHT = 64.dp
private val DOCK_INSET = 6.dp
private val LENS = RoundedCornerShape(26.dp)

/** Room left under each page for the mini player and the dock floating over it. */
private val PAGE_BOTTOM = DOCK_HEIGHT + MINI_HEIGHT + 48.dp

@Composable
fun DesktopApp() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var searching by remember { mutableStateOf(false) }
    // What the sidebar's search box last searched for, in a wide window.
    var searchFor by remember { mutableStateOf<String?>(null) }
    // An album, playlist or artist opened from Home, over the page.
    var opened by remember { mutableStateOf<ShelfItem?>(null) }
    var playerOpen by remember { mutableStateOf(false) }
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val song = queue.getOrNull(index)

    @Composable
    fun Page(sidebarSearch: Boolean) {
        val collection = opened
        when {
            collection != null -> CollectionPage(collection, onBack = { opened = null })
            searching -> SearchPage(if (sidebarSearch) searchFor.orEmpty() else null)
            tab == Tab.HOME -> HomePage(onOpen = { opened = it }, onSettings = { tab = Tab.SETTINGS })
            tab == Tab.LIBRARY -> LibraryPage()
            tab == Tab.SETTINGS -> SettingsPage()
            else -> SongPage("Up next", queue, "The queue is empty.", current = index, onPlay = { _, i -> Player.playAt(i) })
        }
    }

    DesktopTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= WIDE) {
                    // A computer-sized window: the pages beside a sidebar, the player along the top.
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(
                            selected = tab.takeUnless { searching || opened != null },
                            searching = searching,
                            onSelect = { tab = it; searching = false; opened = null },
                            onSearch = { q -> searchFor = q; searching = true; opened = null },
                        )
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TopStrip(song, onOpen = { playerOpen = true }, onQueue = { tab = Tab.QUEUE; searching = false; opened = null })
                            CompositionLocalProvider(LocalPageBottom provides 24.dp, LocalWide provides true) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    Box(Modifier.fillMaxSize().align(Alignment.TopCenter).widthIn(max = 1100.dp)) { Page(sidebarSearch = true) }
                                }
                            }
                        }
                    }
                } else {
                    // A narrow window keeps the phone's dock and mini player.
                    Box(Modifier.fillMaxSize().padding(top = DesktopWindow.topInset)) { Page(sidebarSearch = false) }
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (song != null) MiniPlayer(song, onOpen = { playerOpen = true })
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dock(
                                selected = tab.takeUnless { searching || opened != null },
                                onSelect = { tab = it; searching = false; opened = null },
                                modifier = Modifier.weight(1f),
                            )
                            SearchOrb(searching) { searching = !searching; opened = null }
                        }
                    }
                }
                if (playerOpen && song != null) NowPlaying(song, onClose = { playerOpen = false })
            }
        }
    }
}

/** From this width the window gets a sidebar and a player strip instead of the dock. */
private val WIDE = 980.dp

/** Whether the pages sit beside the sidebar, which already carries the mark. */
internal val LocalWide = staticCompositionLocalOf { false }

/** Room under each page's last row: above the dock and mini player, or a little in a wide window. */
internal val LocalPageBottom = staticCompositionLocalOf { PAGE_BOTTOM }

// ---- Wide window ---------------------------------------------------------------

/** The sidebar: the app's name, search, and the pages, under the window's own buttons on a Mac. */
@Composable
private fun Sidebar(selected: Tab?, searching: Boolean, onSelect: (Tab) -> Unit, onSearch: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Column(
        Modifier.width(240.dp).fillMaxHeight().background(scheme.surfaceContainerLow)
            .padding(top = DesktopWindow.topInset + 16.dp, start = 12.dp, end = 12.dp, bottom = 16.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(rememberLogo(Logo.MARK), null, Modifier.size(width = 30.dp, height = 23.dp))
            Text("OpenTune", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        }
        TextField(
            query, { query = it },
            placeholder = { Text("Search", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(20.dp)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp)) } },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = scheme.surfaceContainerHigh,
                unfocusedContainerColor = scheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query.trim().takeIf { it.isNotEmpty() }?.let(onSearch) }),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("MUSIC", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
        Tab.entries.filter { it.inDock }.forEach { t -> SidebarItem(t.label, if (t == selected) t.selectedIcon else t.icon, t == selected) { onSelect(t) } }
        SidebarItem("Search", Icons.Rounded.Search, searching) { query.trim().takeIf { it.isNotEmpty() }?.let(onSearch) ?: onSearch("") }
        Spacer(Modifier.weight(1f))
        val settings = Tab.SETTINGS
        SidebarItem(settings.label, if (settings == selected) settings.selectedIcon else settings.icon, settings == selected) { onSelect(settings) }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * The player along the top of a wide window: the controls on the left, the
 * song in a capsule in the middle with a thin line for where it is, and
 * volume, like and the queue on the right.
 */
@Composable
private fun TopStrip(song: Song?, onOpen: () -> Unit, onQueue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val volume by Player.volume.collectAsState()
    val liked by Library.liked.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(DesktopWindow.topInset + 64.dp).padding(top = DesktopWindow.topInset).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = Player::previous, enabled = song != null) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(26.dp)) }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (song != null) scheme.onSurface else scheme.surfaceContainerHigh)
                    .clickable(enabled = song != null, onClick = Player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = scheme.surface, strokeWidth = 2.dp)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = scheme.surface, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = Player::next, enabled = song != null) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp)) }
        }
        Box(Modifier.weight(1f).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            NowPlayingCapsule(song, error, onOpen)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (song != null) {
                val isLiked = liked.any { it.videoId == song.videoId }
                IconButton(onClick = { Library.setLiked(song, !isLiked) }) {
                    Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            var level by remember { mutableFloatStateOf(volume.toFloat()) }
            Slider(
                value = level, onValueChange = { level = it; Player.setVolume(it.toDouble()) },
                colors = SliderDefaults.colors(inactiveTrackColor = scheme.onSurface.copy(alpha = 0.18f)),
                modifier = Modifier.width(110.dp).padding(horizontal = 6.dp),
            )
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", tint = scheme.onSurfaceVariant) }
        }
    }
}

/** The song playing, in a rounded box with a thin progress line along its foot; click to open the player. */
@Composable
private fun NowPlayingCapsule(song: Song?, error: String?, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(48.dp).clip(shape).background(scheme.surfaceContainer)) {
        if (song == null) {
            Text("Nothing playing", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val progress = rememberProgress(song)
        Row(Modifier.fillMaxSize().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.thumbnailUrl, 48.dp, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    error ?: song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                )
            }
            Text(
                "${Time.format(progress.positionMs)} / ${Time.format(progress.durationMs)}",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp),
            )
        }
        // Where the song is; clicking along the line seeks there.
        BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp)) {
            val width = constraints.maxWidth.toFloat()
            Box(
                Modifier.fillMaxSize().pointerInput(progress.durationMs) {
                    detectTapGestures { at -> if (progress.durationMs > 0) Player.seekTo((at.x / width * progress.durationMs).toLong()) }
                },
            ) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.fraction()).height(2.dp).background(scheme.primary))
            }
        }
    }
}

// ---- Chrome ------------------------------------------------------------------

/** The phone's floating pill: a frosted bar, the open tab in a tinted lens. */
@Composable
private fun Dock(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val pill = RoundedCornerShape(percent = 50)
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.height(DOCK_HEIGHT).floating(pill).padding(DOCK_INSET)) {
        Tab.entries.filter { it.inDock }.forEach { t ->
            val on = t == selected
            val ink = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(LENS)
                    .then(
                        if (on) {
                            Modifier
                                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.14f))))
                                .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f))), LENS)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (on) t.selectedIcon else t.icon, null, tint = ink, modifier = Modifier.size(25.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Search on its own: a frosted circle that fills with the accent while search is open. */
@Composable
private fun SearchOrb(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(DOCK_HEIGHT)
            .floating(CircleShape)
            .padding(DOCK_INSET)
            .clip(CircleShape)
            .background(if (selected) scheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Search, "Search", tint = if (selected) scheme.onPrimary else scheme.onSurface, modifier = Modifier.size(26.dp))
    }
}

/** Now playing above the dock: cover, names, play inside a progress ring, next. */
@Composable
private fun MiniPlayer(song: Song, onOpen: () -> Unit) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val progress = rememberProgress(song)
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().height(MINI_HEIGHT).floating(shape).clickable(onClick = onOpen).padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.thumbnailUrl, 44.dp, RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                error ?: song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = Player::toggle), contentAlignment = Alignment.Center) {
            ProgressRing(progress::fraction, 2.5.dp, Modifier.fillMaxSize().padding(3.dp))
            if (loading) CircularProgressIndicator(Modifier.fillMaxSize().padding(3.dp), strokeWidth = 2.5.dp)
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(24.dp))
        }
        IconButton(onClick = Player::next) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(26.dp)) }
    }
}

/**
 * The full player over everything: big cover, names, seek bar, controls,
 * volume. Lyrics sit beside it in a wide window, or take the cover's place
 * in a narrow one.
 */
@Composable
private fun NowPlaying(song: Song, onClose: () -> Unit) {
    var showLyrics by remember { mutableStateOf(true) }
    val theme by AppSettings.theme.collectAsState()
    val amoled = theme.playerBackground == com.opentune.data.settings.PlayerBackground.AMOLED
    val gradient = theme.playerBackground == com.opentune.data.settings.PlayerBackground.GRADIENT
    val bg = if (amoled) Color.Black else Color(0xff17171b)
    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
    Box(Modifier.fillMaxSize().background(bg).clickable(enabled = false) {}) {
        if (!amoled && !gradient) {
            AsyncImage(Subsonic.resolveCover(song.thumbnailUrl), null, contentScale = ContentScale.Crop, alpha = 0.18f, modifier = Modifier.fillMaxSize().blur(48.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.4f), bg))))
        }
        if (gradient) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, bg))))
        IconButton(onClick = onClose, modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) { Icon(Icons.Rounded.ExpandMore, "Close", Modifier.size(30.dp)) }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 56.dp)) {
            val beside = maxWidth >= 900.dp
            val (roomHigh, roomWide) = maxHeight to maxWidth
            if (beside && showLyrics) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val coverSize = minOf(maxHeight - 280.dp, maxWidth * 0.8f, 420.dp).coerceAtLeast(140.dp)
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Cover(song.thumbnailUrl, coverSize, RoundedCornerShape(24.dp), Modifier.shadow(24.dp, RoundedCornerShape(24.dp)))
                            Spacer(Modifier.height(28.dp))
                            PlayerControls(song, showLyrics) { showLyrics = !showLyrics }
                        }
                    }
                    LyricsPane(song, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (showLyrics) {
                        LyricsPane(song, Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth())
                    } else {
                        val coverSize = minOf(roomHigh - 280.dp, roomWide * 0.45f, 460.dp).coerceAtLeast(160.dp)
                        Cover(song.thumbnailUrl, coverSize, RoundedCornerShape(24.dp), Modifier.shadow(24.dp, RoundedCornerShape(24.dp)))
                    }
                    Spacer(Modifier.height(28.dp))
                    PlayerControls(song, showLyrics) { showLyrics = !showLyrics }
                }
            }
        }
    }
    }
}

/** Names, like, the lyrics switch, seek bar, the buttons and volume, under the cover. */
@Composable
private fun PlayerControls(song: Song, showLyrics: Boolean, onLyrics: () -> Unit) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val error by Player.error.collectAsState()
    val volume by Player.volume.collectAsState()
    val liked by Library.liked.collectAsState()
    val progress = rememberProgress(song)
    var dragging by remember { mutableStateOf<Float?>(null) }
    var adding by remember { mutableStateOf(false) }
    var timing by remember { mutableStateOf(false) }
    val sleep by Player.sleep.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    error ?: song.artist, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLyrics) {
                Icon(
                    Icons.Rounded.Lyrics, if (showLyrics) "Hide lyrics" else "Show lyrics",
                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Box {
                IconButton(onClick = { adding = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") }
                AddToPlaylistMenu(song, adding) { adding = false }
            }
            IconButton(onClick = { Library.setLiked(song, !isLiked) }) {
                Icon(
                    if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)) {
            Slider(
                value = dragging ?: progress.fraction(),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { Player.seekTo((it * progress.durationMs).toLong()) }; dragging = null },
                enabled = progress.durationMs > 0,
                colors = SliderDefaults.colors(inactiveTrackColor = faint),
            )
            Row {
                Text(Time.format(progress.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(Time.format(progress.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = Player::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(38.dp)) }
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface).clickable(onClick = Player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(34.dp), color = MaterialTheme.colorScheme.surface, strokeWidth = 3.dp)
                } else {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(40.dp),
                    )
                }
            }
            IconButton(onClick = Player::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(38.dp)) }
        }
        Row(Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            var level by remember { mutableFloatStateOf(volume.toFloat()) }
            Slider(
                value = level, onValueChange = { level = it; Player.setVolume(it.toDouble()) },
                colors = SliderDefaults.colors(inactiveTrackColor = faint),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Box {
                IconButton(onClick = { timing = true }) {
                    Icon(
                        Icons.Rounded.Bedtime, "Sleep timer",
                        tint = if (sleep != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(timing, onDismissRequest = { timing = false }) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 300.dp)) { SleepChips(sleep) }
                }
            }
        }
        (sleep as? Player.Sleep.At)?.let { at ->
            var left by remember { mutableLongStateOf(0L) }
            LaunchedEffect(at) { while (true) { left = at.epochMs - System.currentTimeMillis(); delay(1_000) } }
            Text("Stopping in ${Time.format(left)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (sleep == Player.Sleep.EndOfSong) {
            Text("Stopping after this song", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A soft shadow and the phone's frosted fill, so a piece of chrome floats over the page. */
private fun Modifier.floating(shape: Shape): Modifier =
    this.shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
        .clip(shape)
        .background(Color(0xFF232325).copy(alpha = 0.98f))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))), shape)

private class Progress(val positionMs: Long, val durationMs: Long) {
    fun fraction(): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Where the song is, read four times a second. */
@Composable
private fun rememberProgress(song: Song): Progress {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            duration = Player.durationMs()
            delay(250)
        }
    }
    return Progress(position, duration)
}

/** A circle filling clockwise from the top as the song plays. */
@Composable
private fun ProgressRing(progress: () -> Float, stroke: Dp, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arc = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w))
        drawArc(accent, -90f, 360f * progress(), useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(w, cap = StrokeCap.Round))
    }
}

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
                if (!LocalWide.current) {
                    // The sidebar carries the mark and Settings in a wide window; here they sit by the greeting.
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    androidx.compose.foundation.Image(rememberLogo(Logo.MARK), "OpenTune", Modifier.padding(start = 4.dp, end = 24.dp).size(width = 34.dp, height = 26.dp))
                }
            }
        }
        when {
            failed -> item { Message("Couldn't load Home", "Check your connection and try again.", action = "Try again" to ::load) }
            loaded == null -> item { Loading() }
            else -> items(loaded) { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** Good morning, afternoon or evening, as Home on the phone greets you. */
private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun SearchPage(fromSidebar: String? = null) {
    var query by remember { mutableStateOf(fromSidebar.orEmpty()) }
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
    // The sidebar's box asks; this page shows what it found.
    LaunchedEffect(fromSidebar) {
        if (!fromSidebar.isNullOrBlank()) {
            query = fromSidebar
            run()
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        PageTitle(if (fromSidebar.isNullOrBlank()) "Search" else "Results for \"$fromSidebar\"")
        if (fromSidebar == null) TextField(
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item.thumbnailUrl, 132.dp, if (round) CircleShape else MaterialTheme.shapes.medium)
            Column(Modifier.padding(start = 20.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!found.isNullOrEmpty()) {
                    Row(
                        Modifier.padding(top = 12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .clickable { Player.playAll(found, 0) }.padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
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
            found == null -> Loading()
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalPageBottom.current)) {
        itemsIndexed(songs, key = { i, s -> "$i:${s.videoId}" }) { i, song ->
            val isLiked = liked.any { it.videoId == song.videoId }
            val isCurrent = if (current >= 0) i == current else song.videoId == playingId
            var adding by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable { onPlay(songs, i) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(song.thumbnailUrl, 52.dp, MaterialTheme.shapes.small)
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
                IconButton(onClick = { Library.setLiked(song, !isLiked) }) {
                    Icon(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Unlike" else "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    Column(Modifier.width(160.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(bottom = 4.dp)) {
        Cover(item.thumbnailUrl, 160.dp, if (round) CircleShape else MaterialTheme.shapes.medium)
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun Cover(url: String?, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    AsyncImage(
        // A song on your own server carries a reference to its cover, without the credentials.
        model = Subsonic.resolveCover(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** A page's big bold name, as the phone's pages start. */
@Composable
internal fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
internal fun Message(title: String, message: String?, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
