package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.opentune.data.innertube.Innertube
import com.opentune.data.innertube.InnertubeParser
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.lyrics.activeIndex
import com.opentune.data.model.ArtistPage
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.ui.components.FocusRing
import com.opentune.ui.components.glass
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** How wide the sidebar is (folded to a rail of icons on a narrow window, or by hand), and the column on the right. */
internal val SIDEBAR_WIDTH = 260.dp
internal val SIDEBAR_FOLDED = 72.dp
internal val PANEL_WIDTH = 320.dp
/** How tall the player bar across the top of the page is, under the window's own title bar. */
internal val TOP_BAR_HEIGHT = 64.dp

/** How tall the sidebar's head is where it stands in for the window's title bar. */
private val CAPTION_HEIGHT = 40.dp

/** The colour that marks the page showing: the theme's own accent. */
@Composable
internal fun railAccent(): Color {
    val theme by AppSettings.theme.collectAsState()
    return Color(theme.seedColor)
}

/** The web player's type, as tokens.css and opentune.css set it, in the theme's own face. */
private object ChromeType {
    @Composable fun body(size: Int = 13, line: Int = 16, weight: FontWeight = FontWeight.Normal): TextStyle =
        MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)
}

// ---- The sidebar -------------------------------------------------------------

/**
 * Down the left, as the web player's: OpenTune's mark and name at the top
 * with the button that folds it, Search, Home and Explore, then the Library's
 * lists and your playlists under their headings, and Listen together and
 * Settings at the foot, all on glass. The page showing sits on a soft fill of
 * the accent with a faint ring of it.
 *
 * Folded, it's a rail of icons: [fold] runs from 0 (open) to 1 (folded) as it
 * goes, its drawn width easing between the two while [folded], the room it
 * takes in the window, changes in one step (see DesktopApp). Every icon keeps
 * its place, the names fade, the headings turn into hairlines and the
 * playlists fold away; a tooltip names each icon.
 */
@Composable
internal fun Sidebar(
    selected: Any?,
    folded: Boolean,
    fold: Float,
    haze: HazeState?,
    onFold: () -> Unit,
    onPick: (Any) -> Unit,
) {
    val playlists by Library.playlists.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    // The names come back once the sidebar has mostly opened, and go at once when it folds.
    val names by animateFloatAsState(
        if (folded) 0f else 1f,
        when { ui.reduceAnimation -> snap(); folded -> tween(120); else -> tween(220, delayMillis = 140) },
        label = "side names",
    )
    val drawn = lerp(SIDEBAR_WIDTH, SIDEBAR_FOLDED, fold)
    val end = lerp(12.dp, 20.dp, fold)
    Box(Modifier.width(if (folded) SIDEBAR_FOLDED else SIDEBAR_WIDTH).fillMaxHeight()) {
        Column(
            // Drawn at its easing width, which can run past the room it has while it folds; the page
            // slides along beside it rather than being laid out again on every frame.
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(drawn).fillMaxHeight()
                .chromeGlass(haze)
                .padding(top = DesktopWindow.topInset, bottom = 16.dp)
                .semantics { contentDescription = "Main" },
        ) {
            // Where the app draws the window's title bar, the sidebar's head is it: the window's
            // buttons, and room to take hold of the window by.
            if (DesktopWindow.drawsFrame) {
                Box(Modifier.fillMaxWidth().height(CAPTION_HEIGHT)) {
                    Box(Modifier.matchParentSize().windowDrag())
                    WindowButtons(Modifier.align(if (folded) Alignment.Center else Alignment.CenterStart).padding(start = if (folded) 0.dp else 14.dp))
                }
            }
            SideHead(folded, fold, names, onHome = { onPick(Tab.HOME) }, onFold = onFold)
            val row = @Composable { icon: ImageVector, label: String, key: Any, iconSize: Dp ->
                SideRow(icon, label, selected == key, folded, names, iconSize) { onPick(key) }
            }
            LazyColumn(Modifier.weight(1f).padding(start = 20.dp, end = end)) {
                item(key = "search") { row(Icons.Rounded.Search, "Search", Tab.SEARCH, 22.dp) }
                item(key = "home") { row(Icons.Rounded.Home, "Home", Tab.HOME, 22.dp) }
                item(key = "explore") { row(Icons.Rounded.Explore, "Explore", Tab.NEW, 21.dp) }
                item(key = "library-head") { SideHeading("Library", fold) }
                item(key = "library") { row(Icons.Rounded.LibraryMusic, "Library", Tab.LIBRARY, 21.dp) }
                item(key = "recent") { row(Icons.Rounded.History, "Recently played", Shelf.RECENT, 22.dp) }
                item(key = "liked") { row(Icons.Rounded.Favorite, "Liked songs", Shelf.LIKED, 20.dp) }
                item(key = "queue") { row(Icons.AutoMirrored.Rounded.PlaylistPlay, "Up next", Tab.QUEUE, 22.dp) }
                item(key = "playlists-head") { SideHeading("Playlists", fold) }
                item(key = "all") {
                    row(if (playlists.isEmpty()) Icons.Rounded.Add else Icons.AutoMirrored.Rounded.QueueMusic, if (playlists.isEmpty()) "Make a playlist" else "All playlists", Shelf.PLAYLISTS, 21.dp)
                }
                // Each playlist has the same glyph, so the folded rail leaves them out.
                items(playlists, key = { it.id }) { p ->
                    if (fold < 1f) Box(Modifier.fillMaxWidth().height(lerp(38.dp, 0.dp, fold)).clipToBounds().graphicsLayer { alpha = 1f - fold }) {
                        SideRow(Icons.AutoMirrored.Rounded.QueueMusic, p.name, selected == PlaylistKey(p.id), folded, names, 18.dp) { onPick(PlaylistKey(p.id)) }
                    }
                }
            }
            Column(Modifier.padding(start = 20.dp, end = end, top = 12.dp)) {
                row(Icons.Rounded.Groups, "Listen together", Tab.TOGETHER, 22.dp)
                row(Icons.Rounded.Settings, "Settings", Tab.SETTINGS, 21.dp)
            }
        }
    }
}

/**
 * The sidebar's head: OpenTune's wave and name, which go Home, and the button
 * that folds the sidebar. Folded, the wave stays in the middle of the rail
 * and turns into the unfold button under the pointer.
 */
@Composable
private fun SideHead(folded: Boolean, fold: Float, names: Float, onHome: () -> Unit, onFold: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val swap by animateFloatAsState(if (folded && hovered) 1f else 0f, tween(150), label = "unfold shown")
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(if (DesktopWindow.drawsFrame) 52.dp else 72.dp).hoverable(hover)) {
        Box(Modifier.matchParentSize().windowDrag())
        val link = remember { MutableInteractionSource() }
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = lerp(28.dp, 23.dp, fold))
                .graphicsLayer { alpha = 1f - swap }
                .clip(RoundedCornerShape(6.dp))
                .clickable(interactionSource = link, indication = FocusRing(RoundedCornerShape(6.dp)), role = Role.Button, onClick = onHome)
                .semantics(mergeDescendants = true) { contentDescription = "OpenTune, Home" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(26.dp, ink)
            Spacer(Modifier.width(8.dp))
            Text(
                "OpenTune", maxLines = 1, softWrap = false, color = ink,
                style = ChromeType.body(19, 24, FontWeight.Bold).copy(letterSpacing = (-0.19).sp),
                modifier = Modifier.graphicsLayer { alpha = names },
            )
        }
        if (!folded) {
            HoverIcon(
                Icons.AutoMirrored.Rounded.MenuOpen, "Collapse sidebar", size = 32.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).graphicsLayer { alpha = names },
                onClick = onFold,
            )
        } else {
            HoverIcon(
                Icons.Rounded.Menu, "Expand sidebar", size = 32.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).graphicsLayer { alpha = swap },
                onClick = onFold,
            )
        }
    }
}

/** A group's name over its rows ("Library", "Playlists"); folded, a hairline between the groups of icons. */
@Composable
private fun SideHeading(text: String, fold: Float) {
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
    Box(
        Modifier.padding(top = lerp(16.dp, 12.dp, fold), bottom = lerp(0.dp, 12.dp, fold), start = lerp(0.dp, 4.dp, fold), end = lerp(0.dp, 4.dp, fold))
            .fillMaxWidth().height(lerp(23.dp, 1.dp, fold)).clipToBounds()
            .drawBehind { drawRect(line.copy(alpha = line.alpha * fold)) }
            .semantics { heading() },
    ) {
        Text(
            text, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = ChromeType.body(12, 15, FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).graphicsLayer { alpha = 1f - fold },
        )
    }
}

/**
 * One line of the sidebar, the web's nav row: 36dp, a 24dp glyph and the
 * name in 15sp. A soft fill under the pointer, and the glyph grows a touch;
 * the page showing sits on the accent's fill with a faint ring of it, its
 * glyph in the key colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideRow(icon: ImageVector, label: String, selected: Boolean, folded: Boolean, names: Float, iconSize: Dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val ink = MaterialTheme.colorScheme.onSurface
    val key = keyColour()
    val fill by animateColorAsState(
        when { selected -> selectedFill(); hovered -> quinaryFill(); else -> ink.copy(alpha = 0f) }, tween(200), label = "side fill",
    )
    val ring by animateColorAsState(if (selected) key.copy(alpha = 0.3f) else key.copy(alpha = 0f), tween(200), label = "side ring")
    val grow by animateFloatAsState(if (hovered && !still) 1.08f else 1f, tween(350, easing = Motion.Settle), label = "side glyph")
    val shape = RoundedCornerShape(50)
    val row = @Composable {
        Row(
            Modifier.padding(bottom = 2.dp).fillMaxWidth().height(36.dp).clip(shape).background(fill).border(0.5.dp, ring, shape)
                .hoverable(hover)
                .selectable(selected, interactionSource = hover, indication = FocusRing(shape), role = Role.Tab, onClick = onClick)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp).graphicsLayer { scaleX = grow; scaleY = grow }, contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (selected) key else ink, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.width(8.dp))
            // Folded, the name only fades: it stays for screen readers, and the tooltip names the icon.
            Text(
                label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = ink,
                style = ChromeType.body(15, 22),
                modifier = Modifier.graphicsLayer { alpha = names },
            )
        }
    }
    if (!folded) row() else TooltipArea(
        tooltip = {
            Text(
                label, style = ChromeType.body(12, 15, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface,
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
 * Across the top of the page, frosted over what scrolls under it, as the Mac's
 * music app and the web player's pill lay it out: back, then shuffle,
 * previous, play, next and repeat; the song in the middle on its own glass
 * (the LCD: cover, names, a thin seekable line along its foot; click it for
 * the full player); then the heart, the lyrics, queue and Now playing
 * columns, and the volume, whose level slides in under the pointer.
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
    val key = keyColour()
    val frost = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
    BoxWithConstraints(modifier.fillMaxWidth().height(TOP_BAR_HEIGHT + DesktopWindow.topInset)) {
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
        // window): first shuffle and repeat step aside, then the heart.
        val roomy = maxWidth >= 860.dp
        val full = maxWidth >= 700.dp
        // Its empty parts move the window, where the app draws the title bar.
        Box(Modifier.matchParentSize().windowDrag())
        Row(
            Modifier.fillMaxSize().padding(top = DesktopWindow.topInset).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = canGoBack, iconSize = 20.dp, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            if (full) {
                HoverIcon(
                    Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", enabled = has, lit = shuffled, litColour = key,
                    size = 32.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp), onClick = Player::toggleShuffle,
                )
                Spacer(Modifier.width(6.dp))
            }
            BarIcon(Icons.Rounded.SkipPrevious, "Previous", enabled = has, iconSize = 24.dp, onClick = Player::previous)
            Spacer(Modifier.width(2.dp))
            BarButton(if (playing) "Pause" else "Play", enabled = has, size = 38.dp, onClick = Player::toggle) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = barInk(has), modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(2.dp))
            BarIcon(Icons.Rounded.SkipNext, "Next", enabled = has, iconSize = 24.dp, onClick = Player::next)
            if (full) {
                Spacer(Modifier.width(6.dp))
                HoverIcon(
                    if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                    enabled = has, lit = repeat != Player.Repeat.OFF, litColour = key,
                    size = 32.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp), onClick = Player::cycleRepeat,
                )
            }
            // The song, centred in what's left between the controls either side.
            Box(Modifier.weight(1f).padding(horizontal = if (roomy) 18.dp else 10.dp), contentAlignment = Alignment.Center) {
                Lcd(song, onOpenPlayer, Modifier.widthIn(max = 600.dp).fillMaxWidth())
            }
            BarActions(song, panel, onPanel, panelRoom, heart = roomy)
        }
    }
}

/** The ink of the bar's transport: the full ink, faded while there's nothing to play. */
@Composable
private fun barInk(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

/**
 * A button on the bar, as the web player's: a rounded square that a soft
 * fill comes up behind under the pointer and that gives a little when
 * pressed, both over 150ms; faded while it can't be used.
 */
@Composable
private fun BarButton(label: String, enabled: Boolean, size: Dp = 34.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    val fill by animateColorAsState(if (hovered && enabled) quinaryFill() else ink.copy(alpha = 0f), tween(150), label = "bar fill")
    Box(
        Modifier.size(size).pressGive(hover, enabled).clip(shape).background(fill).hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, enabled: Boolean, iconSize: Dp, onClick: () -> Unit) =
    BarButton(label, enabled, onClick = onClick) { Icon(icon, null, tint = barInk(enabled), modifier = Modifier.size(iconSize)) }

/**
 * The bar's right end: the heart, the three columns' switches and the
 * speaker. Under the pointer (or the keyboard) the speaker slides the volume
 * level in where the switches were, as the web player's does; they fade out
 * until the pointer leaves.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BarActions(song: Song?, panel: SidePanel?, onPanel: (SidePanel) -> Unit, panelRoom: Boolean, heart: Boolean) {
    val has = song != null
    val key = keyColour()
    val liked by Library.liked.collectAsState()
    val isLiked = song != null && liked.any { it.videoId == song.videoId }
    var open by remember { mutableStateOf(false) }
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val shown by animateFloatAsState(if (open) 1f else 0f, if (still) snap() else tween(200, easing = Motion.Emphasized), label = "volume shown")
    val nudge = with(LocalDensity.current) { 8.dp.toPx() }
    val square = RoundedCornerShape(8.dp)
    Box(
        Modifier.onPointerEvent(PointerEventType.Exit) { open = false }
            .onFocusChanged { if (!it.hasFocus) open = false },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.graphicsLayer { alpha = 1f - shown }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (heart) HoverIcon(
                    if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) "Remove from Liked" else "Like",
                    enabled = has, lit = isLiked, litColour = key, size = 32.dp, iconSize = 18.dp, shape = square,
                ) { song?.let { Library.setLiked(it, !isLiked) } }
                if (panelRoom) {
                    HoverIcon(Icons.Rounded.Lyrics, "Lyrics", enabled = has, lit = panel == SidePanel.LYRICS, litColour = key, size = 32.dp, iconSize = 18.dp, shape = square) { onPanel(SidePanel.LYRICS) }
                    HoverIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", enabled = has, lit = panel == SidePanel.QUEUE, litColour = key, size = 32.dp, iconSize = 19.dp, shape = square) { onPanel(SidePanel.QUEUE) }
                    HoverIcon(
                        if (panel == SidePanel.NOW) Icons.Rounded.Info else Icons.Outlined.Info, "Now playing", enabled = has, lit = panel == SidePanel.NOW, litColour = key,
                        size = 32.dp, iconSize = 18.dp, shape = square,
                    ) { onPanel(SidePanel.NOW) }
                }
            }
            VolumeSpeaker(
                Modifier.onPointerEvent(PointerEventType.Enter) { open = true }.onFocusChanged { if (it.hasFocus) open = true },
                shape = square,
            )
        }
        // Over the switches while it's out, so a press there sets the level rather than reaching them.
        if (shown > 0f) {
            Box(
                Modifier.padding(end = 36.dp).width(112.dp).height(32.dp)
                    .graphicsLayer { alpha = shown; translationX = (1f - shown) * nudge },
                contentAlignment = Alignment.Center,
            ) { VolumeLevel() }
        }
    }
}

/**
 * The song in the middle of the player bar, the web player's LCD on its own
 * rounded glass: the cover (dimmed under the pointer, with the sign that it
 * opens the full player), the title over the artist, and a 2dp line along
 * the foot that seeks. With the pointer on the line, the names soften and
 * the time gone and the time left show over its ends. OpenTune's wave when
 * nothing plays.
 */
@Composable
private fun Lcd(song: Song?, onOpenPlayer: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    val error by Player.error.collectAsState()
    val loading by Player.loading.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onLine = remember { MutableInteractionSource() }
    val lineHovered by onLine.collectIsHoveredAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    Box(
        modifier.height(50.dp)
            .glass(shape, MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .hoverable(hover)
            .clickable(enabled = song != null, interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClickLabel = "Open the player", onClick = onOpenPlayer),
    ) {
        if (song == null) {
            LogoMark(26.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), Modifier.align(Alignment.Center))
            return@Box
        }
        val progress = rememberProgress(song)
        val timed = lineHovered || scrub != null
        val soften by animateFloatAsState(if (timed) 1f else 0f, tween(150), label = "names soften")
        val dim by animateFloatAsState(if (hovered) 1f else 0f, tween(200), label = "cover dim")
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp))) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) }, label = "bar cover") { url ->
                    Artwork(url, Modifier.fillMaxSize(), RoundedCornerShape(7.dp))
                }
                Box(
                    Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.OpenInFull, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
            // A new song's names come in with a short fade and a rise of a few pixels.
            val rise = with(LocalDensity.current) { 6.dp.toPx() }.toInt()
            AnimatedContent(
                song.videoId,
                transitionSpec = {
                    if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else (fadeIn(tween(280, easing = Motion.Emphasized)) + slideInVertically(tween(280, easing = Motion.Emphasized)) { rise }) togetherWith fadeOut(tween(100))
                },
                label = "bar names",
                modifier = Modifier.weight(1f).padding(start = 10.dp)
                    .graphicsLayer { alpha = 1f - 0.7f * soften }
                    .then(if (ui.reduceBlur) Modifier else Modifier.blur((3f * soften).dp)),
            ) { _ ->
                Column {
                    Text(
                        song.title.ifBlank { if (loading) "Loading…" else "" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = ChromeType.body(13, 16, FontWeight.SemiBold),
                    )
                    val problem = error
                    if (problem != null) {
                        // What went wrong, and the two ways on: try again, or move to the next song.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(problem, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, style = ChromeType.body(12, 15, FontWeight.Medium), modifier = Modifier.weight(1f, fill = false))
                            LcdLink("Try again", Player::toggle)
                            LcdLink("Skip", Player::next)
                        }
                    } else {
                        Text(
                            song.artist.ifBlank { if (loading) "Loading…" else "" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = ChromeType.body(12, 15, FontWeight.Medium),
                        )
                    }
                }
            }
        }
        SeekLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp), onLine) { scrub = it }
        if (soften > 0f) {
            val at = scrub?.let { (it * progress.durationMs).toLong() } ?: progress.positionMs
            val timeStyle = ChromeType.body(11, 13, FontWeight.SemiBold)
            Text(Time.format(at), style = timeStyle, modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 11.dp).graphicsLayer { alpha = soften })
            Text(
                "-" + Time.format((progress.durationMs - at).coerceAtLeast(0)), style = timeStyle,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 11.dp).graphicsLayer { alpha = soften },
            )
        }
    }
}

/** A word in the LCD that does something (Try again, Skip), underlined under the pointer. */
@Composable
private fun LcdLink(text: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Text(
        text, maxLines = 1, style = ChromeType.body(12, 15, FontWeight.Medium).copy(
            textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        ),
        modifier = Modifier.padding(start = 8.dp).hoverable(hover).clickable(interactionSource = hover, indication = null, role = Role.Button, onClick = onClick),
    )
}

// ---- The column beside the page ----------------------------------------------

/**
 * The column on the right, on the same glass as the sidebar: Now playing (the
 * cover, a few lines of the lyrics, the artist, what's next), the full
 * lyrics, or Up next, with a switch between them at its head. A section
 * coming in fades in with a small rise, as the web player's side panel does.
 */
@Composable
internal fun RightPanel(
    panel: SidePanel,
    song: Song,
    queue: List<Song>,
    index: Int,
    haze: HazeState?,
    onPanel: (SidePanel?) -> Unit,
    onOpenArtist: (ShelfItem) -> Unit,
) {
    val ui by AppSettings.ui.collectAsState()
    val lift = with(LocalDensity.current) { 10.dp.toPx() }
    val edge = glassEdge()
    Column(
        Modifier.width(PANEL_WIDTH).fillMaxHeight().chromeGlass(haze)
            // The hairline where the glass meets the page.
            .drawBehind { drawRect(edge, size = androidx.compose.ui.geometry.Size(0.75.dp.toPx(), size.height)) }
            .padding(top = DesktopWindow.topInset)
            .semantics { contentDescription = panel.label },
    ) {
        Row(Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelSwitch(panel, Modifier.weight(1f)) { onPanel(it) }
            Spacer(Modifier.width(6.dp))
            HoverIcon(Icons.Rounded.Close, "Close", size = 30.dp, iconSize = 17.dp, shape = RoundedCornerShape(8.dp)) { onPanel(null) }
        }
        AnimatedContent(panel, transitionSpec = { Motion.swap(ui.reduceAnimation, lift) }, label = "right panel", modifier = Modifier.fillMaxSize()) { p ->
            when (p) {
                SidePanel.NOW -> NowPlayingCards(song, queue, index, onLyrics = { onPanel(SidePanel.LYRICS) }, onOpenArtist = onOpenArtist)
                SidePanel.LYRICS -> LyricsPane(song, Modifier.fillMaxSize().padding(horizontal = 8.dp))
                SidePanel.QUEUE -> UpNextPanel(queue, index)
            }
        }
    }
}

/**
 * The switch at the head of the column, the web's segmented control: the
 * three sections in a soft track, the chosen one on a raised thumb that
 * slides across to the next one picked.
 */
@Composable
private fun PanelSwitch(selected: SidePanel, modifier: Modifier, onPick: (SidePanel) -> Unit) {
    val still = AppSettings.ui.collectAsState().value.reduceAnimation
    val at by animateFloatAsState(selected.ordinal.toFloat(), if (still) snap() else tween(340, easing = Motion.Settle), label = "switch thumb")
    val dark = darkTheme()
    val thumb = if (dark) Color(0xFF4B4B50) else Color.White
    BoxWithConstraints(modifier.height(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.07f)).padding(3.dp)) {
        // Each section as wide as its name needs, so "Now playing" isn't squeezed into a third.
        val total = SWITCH_WEIGHTS.sum()
        val widths = SWITCH_WEIGHTS.map { maxWidth * (it / total) }
        val starts = widths.runningFold(0.dp) { a, w -> a + w }
        val from = at.toInt().coerceIn(0, widths.lastIndex)
        val to = (from + 1).coerceAtMost(widths.lastIndex)
        val t = at - from
        Box(
            Modifier.offset { IntOffset(lerp(starts[from], starts[to], t).roundToPx(), 0) }.width(lerp(widths[from], widths[to], t)).fillMaxHeight()
                .shadow(2.dp, CircleShape).clip(CircleShape).background(thumb),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            SidePanel.entries.forEachIndexed { i, p ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val chosen = p == selected
                val ink by animateColorAsState(
                    if (chosen || hovered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(200), label = "switch ink",
                )
                Box(
                    Modifier.width(widths[i]).fillMaxHeight().clip(CircleShape).hoverable(hover)
                        .selectable(chosen, interactionSource = hover, indication = FocusRing(CircleShape), role = Role.Tab) { onPick(p) },
                    contentAlignment = Alignment.Center,
                ) { Text(p.label, color = ink, maxLines = 1, softWrap = false, style = ChromeType.body(13, 16, FontWeight.SemiBold)) }
            }
        }
    }
}

/** How wide each of the switch's sections is against the others: Now playing, Lyrics, Up next. */
private val SWITCH_WEIGHTS = listOf(1.3f, 0.85f, 1f)

/**
 * Up next in the column, as the web player's: where the music is playing
 * from, Clear and AutoPlay, then each song still to come in a 55dp row, its
 * cover, names and length. Under the pointer a row fills softly, a cross over
 * its cover takes it out and its ⋯ menu stands where the length was; a click
 * plays it, and dragging it moves it up or down the queue.
 */
@Composable
private fun UpNextPanel(queue: List<Song>, index: Int) {
    val playback by AppSettings.playback.collectAsState()
    val key = keyColour()
    val upcoming = remember(queue, index) { (index + 1..queue.lastIndex).toList() }
    val rowPx = with(LocalDensity.current) { UP_NEXT_ROW.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val from = queue.getOrNull(index)?.albumName?.takeIf { it.isNotBlank() }
            Text(
                from?.let { "Playing from $it" } ?: if (upcoming.size == 1) "1 song after this one" else "${upcoming.size} songs after this one",
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = ChromeType.body(12, 15), modifier = Modifier.weight(1f),
            )
            if (upcoming.isNotEmpty()) {
                Text(
                    "Clear", color = key, style = ChromeType.body(14, 20, FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { Player.clearUpcoming(); Notice.show("Queue cleared") }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            HoverIcon(
                Icons.Rounded.AllInclusive, if (playback.autoplay) "AutoPlay on" else "AutoPlay", lit = playback.autoplay, litColour = key,
                size = 30.dp, iconSize = 17.dp, shape = RoundedCornerShape(8.dp),
            ) { AppSettings.setAutoplay(!playback.autoplay) }
        }
        if (upcoming.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing in Up next", style = ChromeType.body(12, 16, FontWeight.SemiBold))
                Text(
                    if (playback.autoplay) "AutoPlay will find similar music when this song ends." else "Songs you play next show here.",
                    style = ChromeType.body(12, 16), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 40.dp)) {
            items(upcoming, key = { "$it:${queue[it].videoId}" }) { i ->
                val lifted = dragging == i
                UpNextRow(
                    queue[i], i,
                    modifier = Modifier.zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { if (lifted) { translationY = dragOffset; shadowElevation = 16f; shape = RoundedCornerShape(14.dp); clip = true } }
                        .pointerInput(i, queue.size) {
                            detectDragGestures(
                                onDragStart = { dragging = i; dragOffset = 0f },
                                onDragEnd = {
                                    val to = (i + (dragOffset / rowPx).roundToInt()).coerceIn(index + 1, queue.lastIndex)
                                    dragging = -1; dragOffset = 0f
                                    if (to != i) Player.move(i, to)
                                },
                                onDragCancel = { dragging = -1; dragOffset = 0f },
                            ) { change, amount -> change.consume(); dragOffset += amount.y }
                        },
                    lifted = lifted,
                )
            }
        }
    }
}

private val UP_NEXT_ROW = 55.dp

@Composable
private fun UpNextRow(song: Song, at: Int, modifier: Modifier, lifted: Boolean) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    // At once under the pointer, easing away when it leaves, as the web's rows do.
    val fill by animateColorAsState(
        if (hovered || lifted) quinaryFill() else ink.copy(alpha = 0f), if (hovered) snap() else tween(150), label = "row fill",
    )
    val tools by animateFloatAsState(if (hovered || lifted) 1f else 0f, tween(120), label = "row tools")
    SongContextMenu(song, queueIndex = at) {
        Row(
            modifier.fillMaxWidth().height(UP_NEXT_ROW).clip(shape).background(fill).hoverable(hover)
                .clickable(interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClickLabel = "Play") { Player.playAt(at) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp)) {
                Artwork(song.thumbnailUrl, Modifier.fillMaxSize(), RoundedCornerShape(10.dp))
                if (hovered) {
                    Box(
                        Modifier.offset((-6).dp, (-6).dp).size(22.dp).shadow(3.dp, CircleShape).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Button) { Player.removeAt(at) }
                            .semantics { contentDescription = "Remove ${song.title}" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, null, tint = ink, modifier = Modifier.size(12.dp)) }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = ChromeType.body())
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = ChromeType.body())
            }
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    song.durationText.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = ChromeType.body(),
                    modifier = Modifier.graphicsLayer { alpha = 1f - tools },
                )
                // Always there, only faded out, so its menu stays open once the pointer moves onto it.
                Box(Modifier.graphicsLayer { alpha = tools }) { SongMoreButton(song, queueIndex = at, size = 28.dp) }
            }
        }
    }
}

// ---- Now playing -------------------------------------------------------------

/** Now playing in the column: the cover with the names over its foot, a few lines of the lyrics, the artist, and what's next. */
@Composable
private fun NowPlayingCards(song: Song, queue: List<Song>, index: Int, onLyrics: () -> Unit, onOpenArtist: (ShelfItem) -> Unit) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val colour = rememberCoverColor(song.thumbnailUrl, base)
    val liked by Library.liked.collectAsState()
    val isLiked = liked.any { it.videoId == song.videoId }
    val card = RoundedCornerShape(20.dp)
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "cover") {
            Box(Modifier.fillMaxWidth().clip(card)) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "panel cover") { url ->
                    Artwork(url, Modifier.fillMaxWidth().aspectRatio(1f), card)
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                        Text(song.title, color = Color.White, style = ChromeType.body(17, 22, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.75f), style = ChromeType.body(13, 18), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, idle = Color.White.copy(alpha = 0.85f), size = 22.dp)
                }
            }
        }
        item(key = "lyrics") { LyricsPreview(song, tint(colour, base, 0.42f), onLyrics) }
        item(key = "artist") { AboutArtist(song, onOpenArtist) }
        val next = queue.drop(index + 1).take(4)
        if (next.isNotEmpty()) item(key = "next") {
            Column(Modifier.fillMaxWidth().clip(card).background(quinaryFill()).padding(vertical = 10.dp)) {
                Text("Next up", style = ChromeType.body(15, 20, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                next.forEachIndexed { i, s ->
                    val hover = remember { MutableInteractionSource() }
                    Row(
                        Modifier.padding(horizontal = 6.dp).fillMaxWidth().hoverWash(hover, RoundedCornerShape(14.dp))
                            .clickable(interactionSource = hover, indication = FocusRing(RoundedCornerShape(14.dp)), role = Role.Button) { Player.playAt(index + 1 + i) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(s.thumbnailUrl, Modifier.size(40.dp), RoundedCornerShape(10.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(s.title, style = ChromeType.body(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = ChromeType.body(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colour).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = ChromeType.body(15, 20, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
            if (lyrics != null) CardChip("Show more", onMore)
        }
        Spacer(Modifier.height(10.dp))
        when {
            state is LyricsState.Loading -> repeat(3) { Placeholder(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.8f - it * 0.15f).height(16.dp), RoundedCornerShape(6.dp)) }
            lyrics is Lyrics.Synced -> {
                val at = lyrics.lines.activeIndex(position).coerceAtLeast(0)
                val shown = lyrics.lines.drop(at).take(3)
                AnimatedContent(at, transitionSpec = { fadeIn(tween(260, easing = Motion.Panel)) togetherWith fadeOut(tween(160)) }, label = "preview line") { _ ->
                    Column {
                        shown.forEachIndexed { i, line ->
                            Text(
                                lyricDisplayText(line).ifBlank { "♪" },
                                style = ChromeType.body(17, 23, FontWeight.Bold),
                                color = Color.White.copy(alpha = if (i == 0) 1f else 0.55f - i * 0.12f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            lyrics is Lyrics.Plain -> Text(lyrics.text.lines().filter { it.isNotBlank() }.take(3).joinToString("\n"), style = ChromeType.body(15, 21, FontWeight.SemiBold), color = Color.White, maxLines = 3)
            else -> Text("No lyrics for this one", style = ChromeType.body(13, 18), color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** A small pill on a card that does something (Show more, View artist): a soft white fill, brighter under the pointer. */
@Composable
private fun CardChip(text: String, onClick: () -> Unit, ink: Color = Color.White, fill: Color = Color.White) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val back by animateColorAsState(fill.copy(alpha = if (hovered) 0.24f else 0.14f), tween(150), label = "chip")
    Text(
        text, style = ChromeType.body(12, 15, FontWeight.SemiBold), color = ink,
        modifier = Modifier.pressGive(hover, to = 0.96f).clip(CircleShape).background(back).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(CircleShape), role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The artist: their photo, monthly listeners and the start of their story, and a way to their page. */
@Composable
private fun AboutArtist(song: Song, onOpenArtist: (ShelfItem) -> Unit) {
    val artistId = song.artistId
    val page by produceState<ArtistPage?>(null, artistId) {
        value = artistId?.let { id -> runCatching { InnertubeParser.parseArtistPage(Innertube.browse(id)) }.getOrNull() }
    }
    val name = page?.name ?: song.artist.substringBefore(",").substringBefore(" & ")
    val apple by produceState<String?>(null, name, page) {
        // Apple's photo only for the artist whose page this is, not a namesake (see artFor).
        val p = page ?: return@produceState
        val titles = p.songs.map { it.title } + p.sections.flatMap { sec -> sec.items.map { it.title } }
        value = runCatching { com.opentune.data.covers.AppleArtistArtRepository.artFor(name, artistId ?: "", titles)?.heroUrl }.getOrNull()
    }
    val card = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(card).background(quinaryFill())) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Artwork(apple ?: page?.thumbnailUrl ?: song.thumbnailUrl, Modifier.fillMaxSize(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Text("About the artist", style = ChromeType.body(15, 20, FontWeight.SemiBold), color = Color.White, modifier = Modifier.padding(16.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = ChromeType.body(17, 22, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (artistId != null) {
                    CardChip(
                        "View artist", { onOpenArtist(ShelfItem(name, "Artist", page?.thumbnailUrl, null, artistId)) },
                        ink = MaterialTheme.colorScheme.onSurface, fill = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            (page?.monthlyListenerCount ?: page?.subscriberCountText)?.let {
                Text(it, style = ChromeType.body(12, 15), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = ChromeType.body(13, 18), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
