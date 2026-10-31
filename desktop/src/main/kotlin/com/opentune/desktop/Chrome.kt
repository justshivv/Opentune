package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.lazy.items
import com.opentune.ui.components.glass
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import kotlinx.coroutines.delay

/** How wide the sidebar is (folded to icons on a narrow window), and the column on the right. */
internal val SIDEBAR_WIDTH = 248.dp
internal val SIDEBAR_FOLDED = 72.dp
internal val PANEL_WIDTH = 320.dp
/** How tall the player bar across the top of the page is, under the window's own title bar. */
internal val TOP_BAR_HEIGHT = 64.dp
/** How much of the sidebar's and side column's own colour covers the window's backdrop: less over Windows' material. */
@Composable
private fun chromeAlpha(): Float {
    val material by WindowsFrame.activeMaterial.collectAsState()
    return if (material == WindowsFrame.Material.OFF) 0.74f else 0.32f
}

/** How tall the sidebar's head is where it stands in for the window's title bar. */
private val CAPTION_HEIGHT = 40.dp

/** The colour that marks the page showing: the theme's own accent. */
@Composable
internal fun railAccent(): Color {
    val theme by AppSettings.theme.collectAsState()
    return Color(theme.seedColor)
}

// ---- The sidebar -------------------------------------------------------------

/**
 * Down the left, as BitChord's desktop has it: a search box, the main pages,
 * then History, Liked songs and Up next, your playlists by name, and
 * Settings at the foot. The page showing sits on a soft fill. On a narrow
 * window it folds to the icons alone.
 */
@Composable
internal fun Sidebar(selected: Any?, folded: Boolean, onPick: (Any) -> Unit) {
    val playlists by Library.playlists.collectAsState()
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(if (folded) SIDEBAR_FOLDED else SIDEBAR_WIDTH).fillMaxHeight()
                // See-through, so the window's backdrop colours it as it does the page.
                .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = chromeAlpha()))
                .padding(top = DesktopWindow.topInset + if (DesktopWindow.drawsFrame) 0.dp else 12.dp, bottom = 10.dp),
        ) {
            // Where the app draws the window's title bar, the sidebar's head is it: the window's
            // buttons, and room to take hold of the window by.
            if (DesktopWindow.drawsFrame) {
                Box(Modifier.fillMaxWidth().height(CAPTION_HEIGHT)) {
                    Box(Modifier.matchParentSize().windowDrag())
                    WindowButtons(Modifier.align(if (folded) Alignment.Center else Alignment.CenterStart).padding(start = if (folded) 0.dp else 10.dp))
                }
            }
            if (folded) {
                SideRow(Icons.Rounded.Search, "Search", selected == Tab.SEARCH, folded) { onPick(Tab.SEARCH) }
            } else {
                // A box that looks like a field and opens Search, where the typing happens.
                val hover = remember { MutableInteractionSource() }
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .hoverWash(hover, RoundedCornerShape(10.dp))
                        .clickable(interactionSource = hover, indication = null) { onPick(Tab.SEARCH) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Search", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            SideRow(Icons.Rounded.Home, "Home", selected == Tab.HOME, folded) { onPick(Tab.HOME) }
            SideRow(Icons.Rounded.Explore, "Explore", selected == Tab.NEW, folded) { onPick(Tab.NEW) }
            SideRow(Icons.Rounded.LibraryMusic, "Library", selected == Tab.LIBRARY, folded) { onPick(Tab.LIBRARY) }
            if (!folded) SideRow(Icons.Rounded.Search, "Search", selected == Tab.SEARCH, folded) { onPick(Tab.SEARCH) }
            HorizontalDivider(color = divider, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            SideRow(Icons.Rounded.History, "History", selected == Shelf.RECENT, folded) { onPick(Shelf.RECENT) }
            SideRow(Icons.Rounded.Favorite, "Liked songs", selected == Shelf.LIKED, folded) { onPick(Shelf.LIKED) }
            SideRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Up next", selected == Tab.QUEUE, folded) { onPick(Tab.QUEUE) }
            HorizontalDivider(color = divider, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            // Your playlists by name, scrolling on their own between the pages and Settings.
            LazyColumn(Modifier.weight(1f)) {
                items(playlists, key = { it.id }) { p ->
                    SideRow(Icons.AutoMirrored.Rounded.QueueMusic, p.name, selected == PlaylistKey(p.id), folded) { onPick(PlaylistKey(p.id)) }
                }
                item(key = "all") {
                    SideRow(Icons.Rounded.Add, if (playlists.isEmpty()) "Make a playlist" else "All playlists", selected == Shelf.PLAYLISTS, folded, quiet = true) { onPick(Shelf.PLAYLISTS) }
                }
            }
            HorizontalDivider(color = divider, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SideRow(Icons.Rounded.Settings, "Settings", selected == Tab.SETTINGS, folded) { onPick(Tab.SETTINGS) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
    }
}

/** One line of the sidebar: an icon and its name, on a soft fill when it's the page showing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideRow(icon: ImageVector, label: String, selected: Boolean, folded: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(
        when { selected -> onSurface.copy(alpha = 0.10f); hovered -> onSurface.copy(alpha = 0.05f); else -> Color.Transparent }, tween(160), label = "side fill",
    )
    val ink = if (selected) onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (quiet) 0.75f else 1f)
    val row = @Composable {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 1.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                .hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick)
                .padding(horizontal = if (folded) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (folded) Arrangement.Center else Arrangement.Start,
        ) {
            Icon(icon, label, tint = if (selected) railAccent() else ink, modifier = Modifier.size(20.dp))
            if (!folded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    label, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    if (!folded) row() else TooltipArea(
        tooltip = {
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp)),
    ) { row() }
}

// ---- The player bar ----------------------------------------------------------

/**
 * Across the top of the page, frosted over what scrolls under it: back, then
 * shuffle, previous, play, next and repeat; the song in a box in the middle
 * with a thin seekable line along its foot (click it for the full player);
 * then the volume and the lyrics, queue and Now playing columns.
 */
@Composable
internal fun TopBar(
    song: Song?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    panel: SidePanel?,
    onPanel: (SidePanel) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the window has room for the column beside the page; without it, its switches aren't shown. */
    panelRoom: Boolean = true,
) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val shuffled by Player.shuffled.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val has = song != null
    val frost = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(TOP_BAR_HEIGHT + DesktopWindow.topInset)) {
        // Frosted over the page sliding under it; solid with blur turned off. Drawn a hair past the
        // bar's top and sides, so the glass's hairline shows only along its foot, where the page meets it.
        Box(
            Modifier.matchParentSize().layout { measurable, constraints ->
                val over = 1.dp.roundToPx()
                val glass = measurable.measure(
                    constraints.copy(minWidth = constraints.maxWidth + 2 * over, maxWidth = constraints.maxWidth + 2 * over, minHeight = constraints.maxHeight + over, maxHeight = constraints.maxHeight + over),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { glass.place(-over, -over) }
            }.glass(RectangleShape, frost),
        )
        // The song keeps its room as the bar narrows (a column open beside the page, a small
        // window): first the volume folds to its speaker, then shuffle and repeat step aside.
        val roomy = maxWidth >= 900.dp
        val full = maxWidth >= 700.dp
        // Its empty parts move the window, where the app draws the title bar.
        Box(Modifier.matchParentSize().windowDrag())
        Box(Modifier.fillMaxSize().padding(top = DesktopWindow.topInset)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HoverIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = canGoBack, size = 34.dp, iconSize = 20.dp, onClick = onBack)
            Spacer(Modifier.width(8.dp))
            if (full) HoverIcon(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", enabled = has, lit = shuffled, size = 32.dp, iconSize = 18.dp, onClick = Player::toggleShuffle)
            HoverIcon(Icons.Rounded.SkipPrevious, "Previous", enabled = has, size = 34.dp, iconSize = 22.dp, onClick = Player::previous)
            Box(
                Modifier.padding(horizontal = 4.dp).size(36.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (has) 1f else 0.3f))
                    .clickable(enabled = has, onClick = Player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(22.dp))
            }
            HoverIcon(Icons.Rounded.SkipNext, "Next", enabled = has, size = 34.dp, iconSize = 22.dp, onClick = Player::next)
            if (full) HoverIcon(
                if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                enabled = has, lit = repeat != Player.Repeat.OFF, size = 32.dp, iconSize = 18.dp, onClick = Player::cycleRepeat,
            )
            // The song, centred in what's left between the controls either side.
            Box(Modifier.weight(1f).padding(horizontal = if (roomy) 16.dp else 8.dp), contentAlignment = Alignment.Center) {
                NowBox(song, onOpenPlayer, Modifier.widthIn(max = 640.dp).fillMaxWidth())
            }
            VolumeControl(slider = roomy)
            Spacer(Modifier.width(6.dp))
            if (panelRoom) {
                HoverIcon(Icons.Rounded.Lyrics, "Lyrics", enabled = has, lit = panel == SidePanel.LYRICS, size = 32.dp, iconSize = 18.dp) { onPanel(SidePanel.LYRICS) }
                HoverIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", enabled = has, lit = panel == SidePanel.QUEUE, size = 32.dp, iconSize = 19.dp) { onPanel(SidePanel.QUEUE) }
                HoverIcon(if (panel == SidePanel.NOW) Icons.Rounded.Info else Icons.Outlined.Info, "Now playing", enabled = has, lit = panel == SidePanel.NOW, size = 32.dp, iconSize = 18.dp) { onPanel(SidePanel.NOW) }
            }
        }
        }
    }
}

/** The song in the middle of the player bar: cover, names and time, the line along its foot; a quiet note when nothing plays. */
@Composable
private fun NowBox(song: Song?, onOpenPlayer: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val error by Player.error.collectAsState()
    val hover = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        // A box of its own colour, so it reads as one over whatever the bar shows through it.
        modifier.height(46.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .hoverWash(hover, shape)
            .clickable(enabled = song != null, interactionSource = hover, indication = null, onClick = onOpenPlayer),
    ) {
        if (song == null) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(20.dp))
            return@BoxWithConstraints
        }
        // Too narrow for the time as well: the names keep the room.
        val timed = maxWidth >= 340.dp
        val progress = rememberProgress(song)
        Row(Modifier.fillMaxSize().padding(start = 5.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "bar cover") { url ->
                Artwork(url, Modifier.size(36.dp), RoundedCornerShape(7.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    error ?: song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (timed) Text(
                "${Time.format(progress.positionMs)} / ${Time.format(progress.durationMs)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeekLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp))
    }
}

// ---- Now playing -------------------------------------------------------------

/**
 * The column on the right, as BitChord's desktop has it: Now playing (the
 * cover, a few lines of the lyrics, the artist), the full lyrics, or the
 * queue, with a switch between them at its head.
 */
@Composable
internal fun RightPanel(panel: SidePanel, song: Song, queue: List<Song>, index: Int, onPanel: (SidePanel?) -> Unit, onOpenArtist: (ShelfItem) -> Unit) {
    Row(Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = chromeAlpha())).padding(top = DesktopWindow.topInset)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SidePanel.entries.forEach { p -> Segment(p.label, p == panel) { onPanel(p) } }
                }
                HoverIcon(Icons.Rounded.Close, "Close", size = 30.dp, iconSize = 17.dp) { onPanel(null) }
            }
            AnimatedContent(panel, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "right panel", modifier = Modifier.fillMaxSize()) { p ->
                when (p) {
                    SidePanel.NOW -> NowPlayingCards(song, queue, index, onLyrics = { onPanel(SidePanel.LYRICS) }, onOpenArtist = onOpenArtist)
                    SidePanel.LYRICS -> LyricsPane(song, Modifier.fillMaxSize().padding(horizontal = 14.dp))
                    SidePanel.QUEUE -> androidx.compose.runtime.CompositionLocalProvider(LocalPageBottom provides 32.dp) {
                        if (queue.isEmpty()) Message("The queue is empty.", null)
                        else SongList(queue, current = index) { _, i -> Player.playAt(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCards(song: Song, queue: List<Song>, index: Int, onLyrics: () -> Unit, onOpenArtist: (ShelfItem) -> Unit) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val colour = rememberCoverColor(song.thumbnailUrl, base)
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "cover") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "panel cover") { url ->
                    Artwork(url, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, idle = Color.White.copy(alpha = 0.8f), size = 22.dp)
                }
            }
        }
        item(key = "lyrics") { LyricsPreview(song, tint(colour, base, 0.42f), onLyrics) }
        item(key = "artist") { AboutArtist(song, base, onOpenArtist) }
        val next = queue.drop(index + 1).take(4)
        if (next.isNotEmpty()) item(key = "next") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(base).padding(vertical = 10.dp)) {
                Text("Next up", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                next.forEachIndexed { i, s ->
                    val hover = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().hoverWash(hover, RoundedCornerShape(0.dp)).clickable(interactionSource = hover, indication = null) { Player.playAt(index + 1 + i) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(s.thumbnailUrl, Modifier.size(40.dp), RoundedCornerShape(6.dp))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The line being sung and the two after it, on a card in the cover's colour; Show more opens the full lyrics. */
@Composable
private fun LyricsPreview(song: Song, colour: Color, onMore: () -> Unit) {
    val state = rememberLyrics(song)
    val lyrics = (state as? LyricsState.Found)?.lyrics
    var position by remember(song.videoId) { mutableLongStateOf(0L) }
    LaunchedEffect(song.videoId) {
        while (true) {
            position = Player.positionMs()
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colour).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics preview", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            if (lyrics != null) {
                Text(
                    "Show more", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state is LyricsState.Loading -> repeat(3) { Placeholder(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.8f - it * 0.15f).height(16.dp), RoundedCornerShape(6.dp)) }
            lyrics is Lyrics.Synced -> {
                val at = lyrics.lines.activeIndex(position).coerceAtLeast(0)
                val shown = lyrics.lines.drop(at).take(3)
                AnimatedContent(at, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "preview line") { _ ->
                    Column {
                        shown.forEachIndexed { i, line ->
                            Text(
                                lyricDisplayText(line).ifBlank { "♪" },
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = if (i == 0) 1f else 0.55f - i * 0.12f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            lyrics is Lyrics.Plain -> Text(lyrics.text.lines().filter { it.isNotBlank() }.take(3).joinToString("\n"), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 3)
            else -> Text("No lyrics for this one", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** The artist: their photo, monthly listeners and the start of their story, and a way to their page. */
@Composable
private fun AboutArtist(song: Song, base: Color, onOpenArtist: (ShelfItem) -> Unit) {
    val artistId = song.artistId
    val page by produceState<ArtistPage?>(null, artistId) {
        value = artistId?.let { id -> runCatching { InnertubeParser.parseArtistPage(Innertube.browse(id)) }.getOrNull() }
    }
    val name = page?.name ?: song.artist.substringBefore(",").substringBefore(" & ")
    val apple by produceState<String?>(null, name) {
        value = runCatching { com.opentune.data.covers.AppleArtistArtRepository.artFor(name)?.heroUrl }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(base)) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Artwork(apple ?: page?.thumbnailUrl ?: song.thumbnailUrl, Modifier.fillMaxSize(), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Text("About the artist", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(14.dp))
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (artistId != null) {
                    Text(
                        "View artist", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onOpenArtist(ShelfItem(name, "Artist", page?.thumbnailUrl, null, artistId)) }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            (page?.monthlyListenerCount ?: page?.subscriberCountText)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
