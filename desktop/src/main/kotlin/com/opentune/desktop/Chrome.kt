package com.opentune.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.opentune.data.account.AccountStore
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

/** The chrome's type, in the theme's own face (DM Sans unless another is chosen). */
private object ChromeType {
    @Composable fun body(size: Int = 13, line: Int = 16, weight: FontWeight = FontWeight.Normal): TextStyle =
        MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)
}

/** Where on the window the avatar at the top bar's right end is, so the account menu can open under it. */
internal object ChromeAnchor {
    var avatar by mutableStateOf<Rect?>(null)
}

// ---- The sidebar -------------------------------------------------------------

/** How tall one row of the sidebar is, how far apart, and how far in from its sides. */
private val CHROME_ROW = 42.dp
private val CHROME_ROW_GAP = 2.dp
private val CHROME_SIDE_INSET = 10.dp

/**
 * Where each of the sidebar's rows sits on the window, so the grey pill can slide from the row
 * that was chosen to the one chosen now, and follow a playlist's row as its list scrolls.
 */
private class ChromePillTrack {
    val rows = mutableStateMapOf<Any, Rect>()
    var origin by mutableStateOf(Offset.Zero)
    /** The playlists' own scrolling list, which a pill under one of them is cut to. */
    var list by mutableStateOf<Rect?>(null)
    /** Where the pill was last drawn, for the next slide to start from. */
    var drawn: Rect? = null
}

/**
 * Down the left, full height: the search field at the very top (what's typed there drives the
 * Search page), then Home, Explore, Library and Search; a hairline; History, Liked songs, your
 * playlists' page and Listen together; another hairline; then every playlist, in a list that
 * scrolls on its own. Settings is pinned at the foot over a hairline, beside the button that
 * folds the sidebar. The page showing sits on a grey pill that slides from row to row.
 *
 * Folded, it's a rail of icons: [fold] runs from 0 (open) to 1 (folded) as it goes, its drawn
 * width easing between the two while [folded], the room it takes in the window, changes in one
 * step (see DesktopApp). Every icon keeps its place, the names fade, the field turns into a
 * search button and the playlists fold away; a tooltip names each icon.
 */
@Composable
internal fun Sidebar(
    selected: Any?,
    folded: Boolean,
    fold: Float,
    haze: HazeState?,
    onFold: () -> Unit,
    query: String = "",
    onQuery: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onPick: (Any) -> Unit,
) {
    // Every playlist, as the Library lists them: yours, then the account's on YouTube Music.
    val local by Library.playlists.collectAsState()
    val account by AccountLibrary.state.collectAsState()
    val playlists = remember(local, account.playlists) { libraryPlaylists(local, account.playlists) }
    val ui by AppSettings.ui.collectAsState()
    // The names come back once the sidebar has mostly opened, and go at once when it folds.
    val names by animateFloatAsState(
        if (folded) 0f else 1f,
        when { ui.reduceAnimation -> snap(); folded -> tween(120); else -> tween(220, delayMillis = 140) },
        label = "side names",
    )
    val drawn = lerp(SIDEBAR_WIDTH, SIDEBAR_FOLDED, fold)
    val track = remember { ChromePillTrack() }
    val field = remember { FocusRequester() }
    // "/" or Ctrl F: the cursor goes into the field at the top, while it's there to take it.
    val asked by Shortcuts.searchFocus.collectAsState()
    LaunchedEffect(asked) { if (asked > 0 && !folded) runCatching { field.requestFocus() } }
    val hairline = ChromeInk.hairline()
    val row = @Composable { icon: ImageVector, label: String, key: Any ->
        ChromeNavRow(icon, label, key, selected == key, folded, names, track) {
            onPick(key)
            if (key == Tab.SEARCH && !folded) runCatching { field.requestFocus() }
        }
    }
    Box(Modifier.width(if (folded) SIDEBAR_FOLDED else SIDEBAR_WIDTH).fillMaxHeight()) {
        Box(
            // Drawn at its easing width, which can run past the room it has while it folds; the page
            // slides along beside it rather than being laid out again on every frame.
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(drawn).fillMaxHeight()
                .chromeSidebarSurface(haze)
                // The 1px hairline where the sidebar meets the page.
                .drawWithContent {
                    drawContent()
                    val w = 1.dp.toPx()
                    drawRect(hairline, Offset(size.width - w, 0f), Size(w, size.height))
                }
                .onGloballyPositioned { track.origin = it.positionInWindow() }
                .semantics { contentDescription = "Main" },
        ) {
            ChromePill(track, selected, ui.reduceAnimation)
            Column(Modifier.fillMaxSize().padding(top = DesktopWindow.topInset)) {
                // Where the app draws the window's title bar, the sidebar's head is it: the window's
                // buttons, and room to take hold of the window by.
                if (DesktopWindow.drawsFrame) {
                    Box(Modifier.fillMaxWidth().height(CAPTION_HEIGHT)) {
                        Box(Modifier.matchParentSize().windowDrag())
                        WindowButtons(Modifier.align(if (folded) Alignment.Center else Alignment.CenterStart).padding(start = if (folded) 0.dp else 16.dp))
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(12.dp).windowDrag())
                }
                ChromeSearchField(
                    query, onQuery, onSearch, folded, fold, names, field,
                    Modifier.padding(horizontal = 12.dp),
                    onOpen = { onPick(Tab.SEARCH) },
                )
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(horizontal = CHROME_SIDE_INSET).selectableGroup(), verticalArrangement = Arrangement.spacedBy(CHROME_ROW_GAP)) {
                    row(Icons.Outlined.Home, "Home", Tab.HOME)
                    row(Icons.Outlined.Explore, "Explore", Tab.NEW)
                    row(Icons.Outlined.LibraryMusic, "Library", Tab.LIBRARY)
                    row(Icons.Outlined.Search, "Search", Tab.SEARCH)
                }
                ChromeDivider()
                Column(Modifier.padding(horizontal = CHROME_SIDE_INSET).selectableGroup(), verticalArrangement = Arrangement.spacedBy(CHROME_ROW_GAP)) {
                    row(Icons.Outlined.History, "History", Shelf.RECENT)
                    row(Icons.Outlined.FavoriteBorder, "Liked songs", Shelf.LIKED)
                    row(if (playlists.isEmpty()) Icons.Rounded.Add else Icons.AutoMirrored.Outlined.QueueMusic, if (playlists.isEmpty()) "New playlist" else "Playlists", Shelf.PLAYLISTS)
                    row(Icons.Outlined.Groups, "Listen together", Tab.TOGETHER)
                }
                if (playlists.isNotEmpty()) ChromeDivider(Modifier.graphicsLayer { alpha = 1f - fold })
                // Each playlist has the same glyph, so the folded rail leaves them out.
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().selectableGroup().onGloballyPositioned { track.list = it.boundsInWindow() },
                    contentPadding = PaddingValues(horizontal = CHROME_SIDE_INSET, vertical = 2.dp),
                ) {
                    items(playlists, key = { it.key }) { p ->
                        if (fold < 1f) Box(
                            Modifier.fillMaxWidth().height(lerp(CHROME_ROW + CHROME_ROW_GAP, 0.dp, fold)).clipToBounds().graphicsLayer { alpha = 1f - fold },
                        ) {
                            ChromeNavRow(
                                Icons.AutoMirrored.Rounded.QueueMusic, p.title, p.key, selected == p.key, folded, names, track, iconSize = 18.dp,
                            ) {
                                when (p) {
                                    is LibPlaylist.Local -> onPick(p.key)
                                    // The account's opens as its page on YouTube Music.
                                    is LibPlaylist.OnAccount -> onPick(p.item)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                // Settings, always in reach, and the button that folds the sidebar.
                if (!folded) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = CHROME_SIDE_INSET, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { row(Icons.Outlined.Settings, "Settings", Tab.SETTINGS) }
                        Spacer(Modifier.width(4.dp))
                        HoverIcon(
                            Icons.AutoMirrored.Rounded.MenuOpen, "Collapse sidebar", size = 34.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.graphicsLayer { alpha = names }, onClick = onFold,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = CHROME_SIDE_INSET, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(CHROME_ROW_GAP)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            HoverIcon(Icons.Rounded.Menu, "Expand sidebar", size = 38.dp, iconSize = 18.dp, shape = RoundedCornerShape(8.dp), onClick = onFold)
                        }
                        row(Icons.Outlined.Settings, "Settings", Tab.SETTINGS)
                    }
                }
            }
        }
    }
}

/** A hairline between the sidebar's groups, as wide as its rows. */
@Composable
private fun ChromeDivider(modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = CHROME_SIDE_INSET + 4.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(ChromeInk.hairline()))
}

/**
 * The grey pill under the page showing. Picking another row slides it there over 340ms, its
 * height and width easing too; with nothing in the sidebar showing (an album opened), it fades.
 * Under a playlist, it's cut to the playlists' list, so it scrolls out of sight with its row.
 */
@Composable
private fun ChromePill(track: ChromePillTrack, selected: Any?, still: Boolean) {
    val fill = ChromeInk.pill()
    val present = selected != null && track.rows[selected] != null
    val shown by animateFloatAsState(if (present) 1f else 0f, tween(if (still) 0 else 160), label = "pill shown")
    // Where this slide starts, read as the choice changes, so the first frame of it is already right.
    val from = remember(selected) { track.drawn.takeIf { shown > 0.05f } }
    val move = remember(selected) { Animatable(if (from == null || still) 1f else 0f) }
    LaunchedEffect(selected) { move.animateTo(1f, tween(340, easing = Motion.Settle)) }
    Box(
        Modifier.fillMaxSize().drawBehind {
            val target = selected?.let { track.rows[it] }
            val t = move.value
            val start = from
            val rect = when {
                target == null -> track.drawn
                start == null || t >= 1f -> target
                else -> androidx.compose.ui.geometry.lerp(start, target, t)
            } ?: return@drawBehind
            track.drawn = rect
            val local = rect.translate(-track.origin)
            val r = CornerRadius(8.dp.toPx())
            val ink = fill.copy(alpha = fill.alpha * shown)
            val cut = track.list?.takeIf { selected is PlaylistKey || selected is AccountPlaylistKey }?.translate(-track.origin)
            if (cut != null) clipRect(cut.left, cut.top, cut.right, cut.bottom) { drawRoundRect(ink, local.topLeft, local.size, r) }
            else drawRoundRect(ink, local.topLeft, local.size, r)
        },
    )
}

/**
 * One row of the sidebar: 42dp, a 20dp outline glyph and the name in 15sp, 14dp in from the
 * pill's edges. The one showing is in white on the pill (drawn by [ChromePill]); the others in
 * a grey-white, with a faint fill under the pointer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChromeNavRow(
    icon: ImageVector,
    label: String,
    key: Any,
    selected: Boolean,
    folded: Boolean,
    names: Float,
    track: ChromePillTrack,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ink by animateColorAsState(if (selected || hovered) MaterialTheme.colorScheme.onSurface else ChromeInk.idle(), tween(160), label = "side ink")
    val wash by animateColorAsState(if (hovered && !selected) ChromeInk.hover() else ChromeInk.hover().copy(alpha = 0f), tween(if (hovered) 0 else 150), label = "side wash")
    DisposableEffect(key) { onDispose { track.rows.remove(key) } }
    val shape = RoundedCornerShape(8.dp)
    val body = @Composable {
        Row(
            Modifier.fillMaxWidth().height(CHROME_ROW)
                .onGloballyPositioned { track.rows[key] = Rect(it.positionInWindow(), it.size.toSize()) }
                .clip(shape).background(wash)
                .hoverable(hover)
                .selectable(selected, interactionSource = hover, indication = FocusRing(shape), role = Role.Tab, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.width(12.dp))
            // Folded, the name only fades: it stays for screen readers, and the tooltip names the icon.
            Text(
                label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = ink,
                style = ChromeType.body(15, 20, if (selected) FontWeight.Medium else FontWeight.Normal),
                modifier = Modifier.graphicsLayer { alpha = names },
            )
        }
    }
    if (!folded) body() else TooltipArea(
        tooltip = { ChromeTooltip(label) },
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp)),
    ) { body() }
}

@Composable
private fun ChromeTooltip(label: String) {
    Text(
        label, style = ChromeType.body(12, 15, FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/**
 * The search field at the top of the sidebar: 40dp, 10dp corners on a faint white fill, the
 * magnifier and "Search". Typing opens the Search page and drives it; Enter searches; Escape
 * clears it. Folded to the rail, it's a button that opens the Search page.
 */
@Composable
private fun ChromeSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    folded: Boolean,
    fold: Float,
    names: Float,
    focus: FocusRequester,
    modifier: Modifier,
    onOpen: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = ChromeInk.secondary()
    val shape = RoundedCornerShape(10.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val base = ChromeInk.field()
    val fill by animateColorAsState(base.copy(alpha = base.alpha + if (focused) 0.03f else if (hovered) 0.02f else 0f), tween(150), label = "field fill")
    val ring by animateColorAsState(ink.copy(alpha = if (focused) 0.22f else 0f), tween(150), label = "field ring")
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(shape).background(fill).border(1.dp, ring, shape).hoverable(hover)
            .then(
                if (folded) Modifier.clickable(interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = "Search" }
                else Modifier,
            )
            .padding(start = lerp(12.dp, 15.dp, fold), end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused) ink else muted, modifier = Modifier.size(18.dp))
        if (folded) return@Row
        Box(Modifier.weight(1f).padding(start = 10.dp).graphicsLayer { alpha = names }, contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search", color = muted, maxLines = 1, style = ChromeType.body(15, 20))
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = ChromeType.body(15, 20).copy(color = ink),
                cursorBrush = SolidColor(ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "Search" }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { onSearch(); true }
                            Key.Escape -> if (query.isNotEmpty()) { onQuery(""); true } else false
                            else -> false
                        }
                    },
            )
        }
        if (query.isNotEmpty()) HoverIcon(Icons.Rounded.Close, "Clear search", size = 26.dp, iconSize = 15.dp, shape = RoundedCornerShape(7.dp)) { onQuery("") }
    }
}

// ---- The player bar ----------------------------------------------------------

/**
 * Across the top of the page, 64dp, over what scrolls under it: OpenTune's mark (it goes Home),
 * then back, shuffle, previous, play, next and repeat as plain icons; the song in the middle
 * in a wide box (cover flush at its left, the names centred, a thin line along its foot that
 * seeks; click it for the full player); then the speaker and a slim volume slider, where the
 * sound goes, the lyrics, Up next and your account's picture, which opens the account menu.
 * As the bar narrows, the slider goes first, then shuffle and repeat, then the mark and the
 * output button, so the song keeps its room.
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
    /** Whether the window has room for the column beside the page; without it, lyrics open the player and Up next its page. */
    panelRoom: Boolean = true,
    onHome: () -> Unit = {},
    onQueuePage: () -> Unit = {},
    onAccount: () -> Unit = {},
    accountOpen: Boolean = false,
) {
    val playing by Player.playing.collectAsState()
    val loading by Player.loading.collectAsState()
    val shuffled by Player.shuffled.collectAsState()
    val repeat by Player.repeat.collectAsState()
    val has = song != null
    val frost = MaterialTheme.colorScheme.background.copy(alpha = 0.86f)
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
        val slider = maxWidth >= 1120.dp
        val full = maxWidth >= 860.dp
        val roomy = maxWidth >= 680.dp
        // On the narrowest window the speaker and lyrics step aside too (the full player has both).
        val tight = maxWidth < 560.dp
        // Its empty parts move the window, where the app draws the title bar.
        Box(Modifier.matchParentSize().windowDrag())
        Row(
            Modifier.fillMaxSize().padding(top = DesktopWindow.topInset).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (roomy) {
                ChromeLogoButton(onHome)
                Spacer(Modifier.width(14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CHROME_ICON_GAP)) {
                ChromeBarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", enabled = canGoBack, onClick = onBack)
                if (full) ChromeBarIcon(Icons.Rounded.Shuffle, if (shuffled) "Shuffle on" else "Shuffle", enabled = has, lit = shuffled, onClick = Player::toggleShuffle)
                ChromeBarIcon(Icons.Rounded.SkipPrevious, "Previous", enabled = has, iconSize = 22.dp, onClick = Player::previous)
                ChromeBarButton(if (playing) "Pause" else "Play", enabled = has, onClick = Player::toggle) { ink ->
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                    else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = ink, modifier = Modifier.size(26.dp))
                }
                ChromeBarIcon(Icons.Rounded.SkipNext, "Next", enabled = has, iconSize = 22.dp, onClick = Player::next)
                if (full) ChromeBarIcon(
                    if (repeat == Player.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (repeat) { Player.Repeat.OFF -> "Repeat off"; Player.Repeat.ALL -> "Repeat all"; Player.Repeat.ONE -> "Repeat this song" },
                    enabled = has, lit = repeat != Player.Repeat.OFF, onClick = Player::cycleRepeat,
                )
            }
            // The song, centred in what's left between the controls either side.
            Box(Modifier.weight(1f).padding(horizontal = if (full) 24.dp else 10.dp), contentAlignment = Alignment.Center) {
                ChromeLcd(song, onOpenPlayer, Modifier.widthIn(max = 650.dp).fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CHROME_ICON_GAP)) {
                // In a Listen together room: who's in, and the way to the room.
                RoomChip { Shortcuts.ask(Shortcuts.Command.TOGETHER) }
                if (!tight) Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeSpeaker()
                    if (slider) ChromeVolumeSlider(Modifier.padding(start = 4.dp, end = 4.dp).width(100.dp))
                }
                if (roomy) ChromeOutputButton()
                if (!tight) ChromeBarIcon(Icons.Outlined.ChatBubbleOutline, "Lyrics", enabled = has, lit = panel == SidePanel.LYRICS, iconSize = 19.dp) {
                    if (panelRoom) onPanel(SidePanel.LYRICS) else onOpenPlayer()
                }
                ChromeBarIcon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Up next", lit = panel == SidePanel.QUEUE) {
                    if (panelRoom && has) onPanel(SidePanel.QUEUE) else onQueuePage()
                }
                Spacer(Modifier.width(2.dp))
                ChromeAvatar(accountOpen, onAccount)
            }
        }
    }
}

/** How far apart the bar's icons are: 32dp places with 8dp between, so 20dp glyphs stand about 20dp apart. */
private val CHROME_ICON_GAP = 8.dp

/** OpenTune's wave at the bar's left end; it goes Home. */
@Composable
private fun ChromeLogoButton(onHome: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ink by animateColorAsState(MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered) 1f else 0.92f), tween(150), label = "logo ink")
    Box(
        Modifier.size(34.dp).pressGive(hover).clip(RoundedCornerShape(8.dp)).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(RoundedCornerShape(8.dp)), role = Role.Button, onClick = onHome)
            .semantics { contentDescription = "OpenTune, Home" },
        contentAlignment = Alignment.Center,
    ) { LogoMark(26.dp, ink) }
}

/**
 * A plain icon on the bar, with no circle behind it: a grey-white that turns white under the
 * pointer and gives a little when pressed; tinted the key colour when it's a switch that's on,
 * with a small dot under it; faded while it can't be used.
 */
@Composable
private fun ChromeBarButton(label: String, enabled: Boolean = true, lit: Boolean = false, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val key = keyColour()
    val ink by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            lit -> key
            hovered -> MaterialTheme.colorScheme.onSurface
            else -> ChromeInk.idle()
        },
        tween(150), label = "bar ink",
    )
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(32.dp).pressGive(hover, enabled).clip(shape).hoverable(hover)
            .clickable(enabled = enabled, interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content(ink)
        if (lit) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(3.dp).clip(CircleShape).background(ink))
    }
}

@Composable
private fun ChromeBarIcon(icon: ImageVector, label: String, enabled: Boolean = true, lit: Boolean = false, iconSize: Dp = 20.dp, onClick: () -> Unit) =
    ChromeBarButton(label, enabled, lit, onClick) { ink -> Icon(icon, null, tint = ink, modifier = Modifier.size(iconSize)) }

/** The speaker: mutes on click, and the wheel over it turns the music up or down. */
@Composable
private fun ChromeSpeaker() {
    val volume by Player.volume.collectAsState()
    var before by remember { mutableStateOf(0.8) }
    val icon = when {
        volume <= 0.0 -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.35 -> Icons.AutoMirrored.Rounded.VolumeMute
        volume < 0.7 -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    Box(Modifier.volumeWheel()) {
        ChromeBarIcon(icon, if (volume <= 0.0) "Unmute" else "Mute") {
            if (volume > 0.0) { before = volume; Player.setVolume(0.0) } else Player.setVolume(before.takeIf { it > 0 } ?: 0.8)
        }
    }
}

/**
 * A slim volume slider, 100dp: a 3dp track filled to the level, its knob showing under the
 * pointer or while dragged. Click or drag along it; the wheel and, once it has the keyboard,
 * the arrow keys move it too.
 */
@Composable
private fun ChromeVolumeSlider(modifier: Modifier = Modifier) {
    val volume by Player.volume.collectAsState()
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val focused by hover.collectIsFocusedAsState()
    val knob by animateFloatAsState(if (hovered || dragging || focused) 1f else 0f, tween(150), label = "volume knob")
    val ink = MaterialTheme.colorScheme.onSurface
    val track = ink.copy(alpha = 0.16f)
    val key = keyColour()
    fun set(x: Float) = Player.setVolume((x / width).coerceIn(0f, 1f).toDouble())
    Box(
        modifier.height(24.dp).hoverable(hover).volumeWheel()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { dragging = true; set(it.x) }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { change, _ -> set(change.position.x) }
            }
            .semantics {
                contentDescription = "Volume"
                progressBarRangeInfo = ProgressBarRangeInfo(volume.toFloat(), 0f..1f)
                setProgress { Player.setVolume(it.coerceIn(0f, 1f).toDouble()); true }
            }
            .focusable(interactionSource = hover)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.DirectionUp -> { Player.setVolume(Player.volume.value + 0.05); true }
                    Key.DirectionLeft, Key.DirectionDown -> { Player.setVolume(Player.volume.value - 0.05); true }
                    else -> false
                }
            }
            .drawBehind {
                val h = 3.dp.toPx()
                val y = (size.height - h) / 2
                val x = size.width * volume.toFloat().coerceIn(0f, 1f)
                drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(if (knob > 0.5f) key else ink.copy(alpha = 0.85f), Offset(0f, y), Size(x, h), CornerRadius(h / 2))
                if (knob > 0f) {
                    val r = 6.dp.toPx() * knob
                    val at = Offset(x.coerceIn(r, size.width - r), size.height / 2)
                    drawCircle(Color.Black.copy(alpha = 0.3f * knob), r + 1.dp.toPx(), at)
                    drawCircle(Color.White, r, at)
                }
            },
    )
}

/** Where the sound goes, from a menu under the headphones: the system's default, or one of this computer's outputs. */
@Composable
private fun ChromeOutputButton() {
    var open by remember { mutableStateOf(false) }
    val selected by AudioDevices.selected.collectAsState()
    var devices by remember { mutableStateOf(emptyList<AudioDevices.Device>()) }
    Box {
        ChromeBarIcon(Icons.Outlined.Headphones, "Play on") { devices = AudioDevices.available(); open = true }
        if (open) WebMenu(MenuAnchor.BelowEnd, { open = false }, "Play on") {
            WebMenuHeader("Play on")
            WebMenuItem("The system's default", checked = selected.isBlank()) { AudioDevices.select("") }
            devices.filter { it.name.isNotBlank() }.forEach { d ->
                WebMenuItem(d.name, checked = selected == d.name) { AudioDevices.select(d.name) }
            }
        }
    }
}

/** Your account's picture at the bar's right end, 32dp and round; it opens the account menu. */
@Composable
private fun ChromeAvatar(open: Boolean, onClick: () -> Unit) {
    val account by AccountStore.account.collectAsState()
    val signedIn by AccountStore.signedIn.collectAsState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ring by animateColorAsState(MaterialTheme.colorScheme.onSurface.copy(alpha = if (open) 0.7f else if (hovered) 0.35f else 0f), tween(150), label = "avatar ring")
    Box(
        Modifier.size(34.dp)
            .onGloballyPositioned { ChromeAnchor.avatar = Rect(it.positionInWindow(), it.size.toSize()) }
            .pressGive(hover).clip(CircleShape).border(1.5.dp, ring, CircleShape).hoverable(hover)
            .clickable(interactionSource = hover, indication = FocusRing(CircleShape), role = Role.Button, onClickLabel = "Open the account menu", onClick = onClick)
            .semantics { contentDescription = if (signedIn) "Account, ${account?.name.orEmpty()}" else "Account" },
        contentAlignment = Alignment.Center,
    ) { ChromeAccountPicture(account?.thumbnailUrl, account?.name, 30.dp) }
}

/** An account's picture, round; its initial on the key colour when it has none, or a person without an account. */
@Composable
internal fun ChromeAccountPicture(url: String?, name: String?, size: Dp) {
    when {
        url != null -> Cover(url, size, CircleShape)
        !name.isNullOrBlank() -> Box(Modifier.size(size).clip(CircleShape).background(keyColour().copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), style = ChromeType.body((size.value * 0.45f).toInt(), (size.value * 0.55f).toInt(), FontWeight.SemiBold))
        }
        else -> Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, null, tint = ChromeInk.idle(), modifier = Modifier.size(size * 0.6f))
        }
    }
}

/**
 * The song in the middle of the bar: a box up to 650dp wide and 56dp tall with 6dp corners on a
 * faint white fill. Playing, the cover sits square and flush at its left (dimmed under the
 * pointer, with the sign that it opens the full player), the title over the artist centred in
 * the rest, and a thin line along its foot that seeks; with the pointer on the line the names
 * soften and the time gone and the time left show at its ends. A heart comes up at the right
 * end under the pointer, and stays on a liked song. Idle, a music note in the middle.
 */
@Composable
private fun ChromeLcd(song: Song?, onOpenPlayer: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val error by Player.error.collectAsState()
    val loading by Player.loading.collectAsState()
    val ui by AppSettings.ui.collectAsState()
    val liked by LikedSongs.ids.collectAsState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val onLine = remember { MutableInteractionSource() }
    val lineHovered by onLine.collectIsHoveredAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    val base = ChromeInk.lcd()
    val fill by animateColorAsState(base.copy(alpha = base.alpha + if (hovered && song != null) 0.025f else 0f), tween(150), label = "lcd fill")
    BoxWithConstraints(
        modifier.height(56.dp).clip(shape).background(fill)
            .hoverable(hover)
            .clickable(enabled = song != null, interactionSource = hover, indication = FocusRing(shape), role = Role.Button, onClickLabel = "Open the player", onClick = onOpenPlayer)
            .semantics { contentDescription = song?.let { "${it.title}, ${it.artist}" } ?: "Nothing playing" },
    ) {
        if (song == null) {
            Icon(Icons.Rounded.MusicNote, null, tint = ChromeInk.secondary(), modifier = Modifier.align(Alignment.Center).size(22.dp))
            return@BoxWithConstraints
        }
        val progress = rememberProgress(song)
        val timed = lineHovered || scrub != null
        val soften by animateFloatAsState(if (timed) 1f else 0f, tween(150), label = "names soften")
        val dim by animateFloatAsState(if (hovered) 1f else 0f, tween(200), label = "cover dim")
        val isLiked = song.videoId in liked
        // A small box keeps its names, without the room either side for the heart.
        val roomy = maxWidth >= 300.dp
        val heart by animateFloatAsState(if (roomy && (hovered || isLiked)) 1f else 0f, tween(150), label = "lcd heart")
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) {
                AnimatedContent(song.thumbnailUrl, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) }, label = "bar cover") { url ->
                    Artwork(url, Modifier.fillMaxSize(), RectangleShape)
                }
                Box(
                    Modifier.matchParentSize().graphicsLayer { alpha = dim }.background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.OpenInFull, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
            // A new song's names come in with a short fade and a rise of a few pixels.
            val rise = with(LocalDensity.current) { 6.dp.toPx() }.toInt()
            AnimatedContent(
                song.videoId,
                transitionSpec = {
                    if (ui.reduceAnimation) fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                    else (fadeIn(tween(280, easing = Motion.Emphasized)) + slideInVertically(tween(280, easing = Motion.Emphasized)) { rise }) togetherWith fadeOut(tween(100))
                },
                contentAlignment = Alignment.Center,
                label = "bar names",
                modifier = Modifier.weight(1f).padding(horizontal = if (roomy) 44.dp else 8.dp)
                    .graphicsLayer { alpha = 1f - 0.7f * soften }
                    .then(if (ui.reduceBlur) Modifier else Modifier.blur((3f * soften).dp)),
            ) { _ ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        song.title.ifBlank { if (loading) "Loading…" else "" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface, style = ChromeType.body(14, 18, FontWeight.Medium),
                    )
                    val problem = error
                    if (problem != null) {
                        // What went wrong, and the two ways on: try again, or move to the next song.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(problem, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error, style = ChromeType.body(12, 16, FontWeight.Medium), modifier = Modifier.weight(1f, fill = false))
                            LcdLink("Try again", Player::toggle)
                            LcdLink("Skip", Player::next)
                        }
                    } else {
                        Text(
                            song.artist.ifBlank { if (loading) "Loading…" else "" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = ChromeInk.secondary(), style = ChromeType.body(13, 17),
                        )
                    }
                }
            }
        }
        if (heart > 0f) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).graphicsLayer { alpha = heart }) {
                LikeButton(isLiked, { Library.setLiked(song, !isLiked) }, idle = ChromeInk.secondary(), size = 18.dp)
            }
        }
        ChromeLcdLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 56.dp), onLine) { scrub = it }
        if (soften > 0f) {
            val at = scrub?.let { (it * progress.durationMs).toLong() } ?: progress.positionMs
            val timeStyle = ChromeType.body(11, 13, FontWeight.SemiBold)
            Text(Time.format(at), style = timeStyle, modifier = Modifier.align(Alignment.BottomStart).padding(start = 66.dp, bottom = 9.dp).graphicsLayer { alpha = soften })
            Text(
                "-" + Time.format((progress.durationMs - at).coerceAtLeast(0)), style = timeStyle,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 9.dp).graphicsLayer { alpha = soften },
            )
        }
    }
}

/**
 * The thin line along the foot of the song's box: 2dp on its bottom edge, 4dp under the pointer;
 * click or drag along it to move through the song. [hover] tells the box when the pointer is on
 * it, and [onScrub] where a drag has got to (null once let go), so the times can show.
 */
@Composable
private fun ChromeLcdLine(progress: Progress, modifier: Modifier, hover: MutableInteractionSource, onScrub: (Float?) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val hovered by hover.collectIsHoveredAsState()
    val thick by animateFloatAsState(if (hovered || dragging != null) 1f else 0.5f, tween(150, easing = Motion.Emphasized), label = "lcd line")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val played = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val shown = dragging ?: progress.fraction()
    fun seekTo(f: Float) { if (progress.durationMs > 0) Player.seekTo((f.coerceIn(0f, 1f) * progress.durationMs).toLong()) }
    fun scrub(f: Float?) { dragging = f; onScrub(f) }
    Box(
        modifier.height(10.dp).hoverable(hover)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(progress.durationMs) { detectTapGestures { seekTo(it.x / width) } }
            .pointerInput(progress.durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub((it.x / width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging?.let(::seekTo); scrub(null) },
                    onDragCancel = { scrub(null) },
                ) { change, _ -> scrub((change.position.x / width).coerceIn(0f, 1f)) }
            }
            .drawBehind {
                val h = 4.dp.toPx() * thick
                val y = size.height - h
                drawRect(track, Offset(0f, y), Size(size.width, h))
                drawRect(played, Offset(0f, y), Size(size.width * shown.coerceIn(0f, 1f), h))
            },
    )
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
    val edge = ChromeInk.hairline()
    Column(
        Modifier.width(PANEL_WIDTH).fillMaxHeight().chromeSidebarSurface(haze)
            // The hairline where the glass meets the page.
            .drawBehind { drawRect(edge, size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
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
        // The songs before this one, the latest first, to play again (issue 50).
        val played = remember(queue, index) { playedIndices(index, queue.size) }
        if (upcoming.isEmpty() && played.isEmpty()) {
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
            if (upcoming.isEmpty()) item(key = "nothing next") {
                Text(
                    "Nothing in Up next", style = ChromeType.body(12, 16), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            if (played.isNotEmpty()) {
                item(key = "played label") {
                    Text(
                        "Played", style = ChromeType.body(12, 16, FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(played, key = { "$it:${queue[it].videoId}" }) { i -> UpNextRow(queue[i], i, Modifier, lifted = false) }
            }
        }
    }
}

/** The queue's places before [index], the latest first: the songs already played, to go back to. */
internal fun playedIndices(index: Int, size: Int): List<Int> = (minOf(index, size) - 1 downTo 0).toList()

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
    val liked by LikedSongs.ids.collectAsState()
    val isLiked = song.videoId in liked
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
