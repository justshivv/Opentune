package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.subsonic.Subsonic

/** The column beside the page: the song's lyrics, or what plays next. */
enum class SidePanel(val label: String) { LYRICS("Lyrics"), QUEUE("Up next") }

/** How wide the sidebar is, and its icons-only form in a narrow window. */
private val SIDEBAR_WIDTH = 224.dp
private val SIDEBAR_COMPACT = 64.dp
private val TOP_BAR_HEIGHT = 60.dp
private val SIDE_PANEL_WIDTH = 340.dp

/** The faint white a row takes under the pointer, and the firmer one when it's the page showing. */
private const val HOVER_ALPHA = 0.06f
private const val SELECTED_ALPHA = 0.12f

// ---- The sidebar -------------------------------------------------------------

/**
 * Down the left of the window: search, the pages, your library's shelves and
 * the playlists you made, with Settings at the foot. A narrow window keeps
 * only the icons.
 */
@Composable
internal fun Sidebar(
    compact: Boolean,
    page: Tab?,
    shelf: Shelf?,
    openPlaylist: String?,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onTab: (Tab) -> Unit,
    onShelf: (Shelf) -> Unit,
    onPlaylist: (String) -> Unit,
) {
    val playlists by Library.playlists.collectAsState()
    val server by Subsonic.server.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val divider = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(if (compact) SIDEBAR_COMPACT else SIDEBAR_WIDTH).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = DesktopWindow.topInset + 14.dp, bottom = 12.dp, start = 10.dp, end = 10.dp),
        ) {
            if (compact) {
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
                    Image(rememberLogo(Logo.MARK), "OpenTune", Modifier.size(26.dp))
                }
            } else {
                Row(Modifier.padding(start = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(rememberLogo(Logo.MARK), null, Modifier.size(24.dp))
                    Text("OpenTune", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                }
                SearchBox(query, onQuery, onSearch)
                Spacer(Modifier.height(14.dp))
            }
            listOf(Tab.HOME, Tab.SEARCH, Tab.LIBRARY, Tab.QUEUE).forEach { t ->
                if (t == Tab.SEARCH && !compact) return@forEach
                SidebarItem(if (page == t && shelf == null) t.selectedIcon else t.icon, t.label, page == t && (t != Tab.LIBRARY || shelf == null && openPlaylist == null), compact) { onTab(t) }
            }
            if (!compact) {
                SectionLabel("Library")
                SidebarItem(Icons.Rounded.Favorite, "Liked songs", shelf == Shelf.LIKED, compact) { onShelf(Shelf.LIKED) }
                SidebarItem(Icons.Rounded.History, "Recently played", shelf == Shelf.RECENT, compact) { onShelf(Shelf.RECENT) }
                if (server != null) SidebarItem(Icons.Rounded.Storage, "Your server", shelf == Shelf.SERVER, compact) { onShelf(Shelf.SERVER) }
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playlists", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    HoverIcon(Icons.Rounded.Add, "New playlist", size = 26.dp) { naming = true }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        SidebarItem(Icons.AutoMirrored.Rounded.PlaylistPlay, p.name, openPlaylist == p.id, compact) { onPlaylist(p.id) }
                    }
                    if (playlists.isEmpty()) {
                        item {
                            Text(
                                "Playlists you make show here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            HorizontalDivider(color = divider, modifier = Modifier.padding(bottom = 8.dp))
            SidebarItem(if (page == Tab.SETTINGS) Tab.SETTINGS.selectedIcon else Tab.SETTINGS.icon, "Settings", page == Tab.SETTINGS, compact) { onTab(Tab.SETTINGS) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
    }
    if (naming) {
        NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            onPlaylist(Library.createPlaylist(name))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** One row of the sidebar: lit white while it's the page showing, a faint wash under the pointer. */
@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(
        onSurface.copy(alpha = if (selected) SELECTED_ALPHA else if (hovered) HOVER_ALPHA else 0f), tween(160), label = "sidebar fill",
    )
    val ink by animateColorAsState(if (selected) onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(160), label = "sidebar ink")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(fill)
            .hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (compact) 10.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(if (compact) 22.dp else 19.dp))
        if (!compact) {
            Text(
                label, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 11.dp),
            )
        }
    }
}

/** The search box at the head of the sidebar; Enter runs the search. */
@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).background(onSurface.copy(alpha = 0.08f)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = muted, modifier = Modifier.size(17.dp))
        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search", color = muted, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                query, onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurface),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }),
                modifier = Modifier.fillMaxWidth().onEnter(onSearch),
            )
        }
        if (query.isNotEmpty()) HoverIcon(Icons.Rounded.Close, "Clear", size = 22.dp) { onQuery("") }
    }
}

/** Enter in a desktop text field, which doesn't send an IME action. */
private fun Modifier.onEnter(run: () -> Unit): Modifier = this.then(
    Modifier.onPreviewKeyEvent { e ->
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
            (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter)
        ) { run(); true } else false
    },
)

// ---- The bar along the top ---------------------------------------------------

/**
 * Along the top of the page: back, the transport (shuffle, previous,
 * play, next, repeat), the song playing with its progress under it, and on
 * the right the lyrics and queue columns and the volume.
 */
@Composable
internal fun TopBar(
    song: Song?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    panel: SidePanel?,
    onPanel: (SidePanel) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val queue by Player.queue.collectAsState()
    val index by Player.index.collectAsState()
    val shuffled by Player.shuffled.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val has = song != null
    Column {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A narrow window keeps the song and the main buttons; shuffle, repeat and the slider go.
        val roomy = maxWidth >= 640.dp
        Row(
            Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT + DesktopWindow.topInset).padding(top = DesktopWindow.topInset, start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoverIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = canGoBack, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            if (roomy) HoverIcon(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", enabled = has, lit = shuffled, iconSize = 18.dp, onClick = Player::toggleShuffle)
            HoverIcon(Icons.Rounded.SkipPrevious, "Previous", enabled = has, iconSize = 24.dp, onClick = Player::previous)
            Box(
                Modifier.size(38.dp).clip(CircleShape).clickable(enabled = has, onClick = Player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                if (loading && has) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                else Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                    tint = if (has) MaterialTheme.colorScheme.onSurface else muted.copy(alpha = 0.4f), modifier = Modifier.size(30.dp),
                )
            }
            HoverIcon(Icons.Rounded.SkipNext, "Next", enabled = has && (index < queue.lastIndex || AppSettings.playback.value.autoplay || repeat == Player.Repeat.ALL), iconSize = 24.dp, onClick = Player::next)
            if (roomy) HoverIcon(
                if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                lit = repeat != Player.Repeat.OFF, iconSize = 18.dp, onClick = Player::cycleRepeat,
            )
            Spacer(Modifier.width(if (roomy) 14.dp else 6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                NowPlayingCard(song, onOpenPlayer, Modifier.widthIn(max = 620.dp).fillMaxWidth())
            }
            Spacer(Modifier.width(14.dp))
            HoverIcon(Icons.Rounded.Lyrics, "Lyrics", enabled = has, lit = panel == SidePanel.LYRICS, iconSize = 19.dp) { onPanel(SidePanel.LYRICS) }
            HoverIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", enabled = has, lit = panel == SidePanel.QUEUE, iconSize = 20.dp) { onPanel(SidePanel.QUEUE) }
            Spacer(Modifier.width(6.dp))
            VolumeControl(slider = roomy)
        }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The song playing, in the middle of the top bar: its cover, its title and
 * artist, and its progress as a thin line along the card's foot that can be
 * clicked or dragged. Clicking the rest opens the full player.
 */
@Composable
private fun NowPlayingCard(song: Song?, onOpen: () -> Unit, modifier: Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(10.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(onSurface.copy(alpha = if (hovered && song != null) 0.10f else 0.06f), tween(160), label = "card fill")
    Box(modifier.height(46.dp).clip(shape).background(fill).hoverable(hover)) {
        if (song == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(rememberLogo(Logo.MARK), null, Modifier.size(22.dp), alpha = 0.5f)
            }
            return@Box
        }
        val error by Player.error.collectAsState()
        val progress = rememberProgress(song)
        var wide by remember { mutableStateOf(true) }
        Row(
            Modifier.fillMaxSize().onSizeChanged { wide = it.width > 420 * density }.clickable(interactionSource = hover, indication = null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) }, label = "card cover") { url ->
                Artwork(url, Modifier.size(46.dp), RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(
                    error ?: listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.albumName).joinToString(" — "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The times, where the card has room for them beside the title.
            if (wide) {
                Text(
                    "${Time.format(progress.positionMs)} / ${Time.format(progress.durationMs)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        SeekLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 46.dp))
    }
}

/** A thin progress line: click or drag along it to move through the song. */
@Composable
private fun SeekLine(progress: Progress, modifier: Modifier) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thick by androidx.compose.animation.core.animateDpAsState(if (hovered || dragging != null) 4.dp else 2.dp, tween(140), label = "seek line")
    val color = MaterialTheme.colorScheme.onSurface
    val shown = dragging ?: progress.fraction()
    fun seekTo(f: Float) { if (progress.durationMs > 0) Player.seekTo((f.coerceIn(0f, 1f) * progress.durationMs).toLong()) }
    Box(
        modifier.height(10.dp).hoverable(hover)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(progress.durationMs) { detectTapGestures { seekTo(it.x / width) } }
            .pointerInput(progress.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = it.x / width },
                    onDragEnd = { dragging?.let(::seekTo); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) }
            }
            .drawBehind {
                val h = thick.toPx()
                val y = size.height - h
                drawRoundRect(color.copy(alpha = 0.14f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(color.copy(alpha = 0.85f), Offset(0f, y), Size(size.width * shown.coerceIn(0f, 1f), h), CornerRadius(h / 2))
            },
    )
}

/** The speaker, muting on click, and a short slider beside it. */
@Composable
private fun VolumeControl(slider: Boolean) {
    val volume by Player.volume.collectAsState()
    var before by remember { mutableStateOf(0.8) }
    val icon = when {
        volume <= 0.0 -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.5 -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    HoverIcon(icon, if (volume <= 0.0) "Unmute" else "Mute", iconSize = 19.dp) {
        if (volume > 0.0) { before = volume; Player.setVolume(0.0) } else Player.setVolume(before.takeIf { it > 0 } ?: 0.8)
    }
    if (!slider) return
    var width by remember { mutableFloatStateOf(1f) }
    val color = MaterialTheme.colorScheme.onSurface
    fun set(x: Float) = Player.setVolume((x / width).coerceIn(0f, 1f).toDouble())
    Box(
        Modifier.width(96.dp).height(20.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } }
            .drawBehind {
                val h = 3.dp.toPx()
                val y = (size.height - h) / 2
                drawRoundRect(color.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(color.copy(alpha = 0.8f), Offset(0f, y), Size(size.width * volume.toFloat(), h), CornerRadius(h / 2))
                drawCircle(color, 5.dp.toPx(), Offset(size.width * volume.toFloat(), size.height / 2))
            },
    )
}

/** A bare icon that a soft circle comes up behind under the pointer; [lit] is white rather than grey. */
@Composable
internal fun HoverIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    lit: Boolean = false,
    size: Dp = 34.dp,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(onSurface.copy(alpha = if (hovered && enabled) 0.09f else 0f), tween(140), label = "icon fill")
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        lit -> onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(fill).hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = ink, modifier = Modifier.size(iconSize))
        // A dot under a lit toggle, as the Mac's music app marks shuffle and repeat.
        if (lit) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp).clip(CircleShape).background(onSurface))
    }
}

// ---- Beside the page ---------------------------------------------------------

/** The lyrics or the queue, in a column at the page's right, with a switch between them at its head. */
@Composable
internal fun SidePanelColumn(panel: SidePanel, song: Song, queue: List<Song>, index: Int, onPanel: (SidePanel?) -> Unit) {
    Row(Modifier.width(SIDE_PANEL_WIDTH).fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SidePanel.entries.forEach { p -> Segment(p.label, p == panel) { onPanel(p) } }
                Spacer(Modifier.weight(1f))
                HoverIcon(Icons.Rounded.Close, "Close", iconSize = 18.dp) { onPanel(null) }
            }
            AnimatedContent(panel, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "side panel", modifier = Modifier.fillMaxSize()) { p ->
                when (p) {
                    SidePanel.LYRICS -> LyricsPane(song, Modifier.fillMaxSize().padding(horizontal = 18.dp))
                    SidePanel.QUEUE -> CompositionLocalProvider(LocalPageBottom provides 16.dp) {
                        if (queue.isEmpty()) Message("The queue is empty.", null)
                        else SongList(queue, current = index) { _, i -> Player.playAt(i) }
                    }
                }
            }
        }
    }
}

/** A choice in a row of them: solid when chosen, plain text otherwise. */
@Composable
internal fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when { selected -> onSurface; hovered -> onSurface.copy(alpha = 0.08f); else -> Color.Transparent }, tween(160), label = "segment",
    )
    val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant, tween(160), label = "segment ink")
    Text(
        label, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * Behind the page, the playing cover's colour as a soft wash from the top,
 * easing to a new song's, so the window takes a little of the music's colour
 * while the chrome stays grey.
 */
@Composable
internal fun PageBackdrop(cover: String?) {
    val base = MaterialTheme.colorScheme.background
    val seed = rememberCoverColor(cover, base)
    val wash by animateColorAsState(if (cover == null) base else tint(seed, base, 0.22f), tween(800), label = "page wash")
    // Eased toward the page colour, so there's no line where the wash ends.
    Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(0f to wash, 0.45f to androidx.compose.ui.graphics.lerp(wash, base, 0.6f), 1f to base)))
}
