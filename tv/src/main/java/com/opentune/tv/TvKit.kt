package com.opentune.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import kotlinx.coroutines.CancellationException

/*
 * TvKit: the building blocks every TV screen is made of. Use these rather than drawing your own,
 * so focus, size and colour stay the same everywhere (tokens are in TvTheme.kt).
 *
 * Focus, in short: anything the remote can land on is built on [rememberTvFocus] and
 * [tvFocusTarget] (or on [TvSurface], which does both). The focused thing wears the accent ring
 * ([tvFocusRing]), takes a lighter fill, and at most grows to [TvMotion.FocusScale]. Give list and
 * shelf items a `focusKey` that is unique on the page, and the shell puts the focus back on that
 * item when the viewer comes back to the page (see [PageMemory]).
 */

// ---- Page memory: what the shell keeps for each page while another is on top of it ----

/**
 * What a page remembers while it is under another one: which item had the focus, and the data it
 * loaded with [rememberLoad], so Back returns to the same place without a reload. The shell
 * ([TvRoot]) makes one per page on the stack and provides it as [LocalPageMemory]; the kit's
 * focusable pieces read and write it, so pages need do nothing but pass `focusKey`s.
 */
@Stable
class PageMemory internal constructor(val id: Int) {
    /** The `focusKey` of what last had the focus on this page; null for something without a key. */
    var focusKey: String? = null
        internal set

    /** True while the page should take the focus: just opened, or just come back to. */
    internal var wantsFocus by mutableStateOf(true)

    /** Bumped when a focusable piece arrives while [wantsFocus], so the shell tries again. */
    internal var arrivals by mutableIntStateOf(0)

    internal val requesters = HashMap<String, FocusRequester>()
    internal var entry: FocusRequester? = null
    internal val loads = HashMap<Any?, Load<*>>()
}

/** The memory of the page being drawn; null outside a page (the shell's bar, tests that draw a piece alone). */
val LocalPageMemory = staticCompositionLocalOf<PageMemory?> { null }

// ---- Loading a page's data ----

/** What a page's data is doing: on its way, here, or not coming. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/**
 * [block]'s answer as a [Load], read again when [key] changes or the returned retry is called.
 * Kept in the page's [PageMemory], so coming back to the page shows it at once, scrolled and
 * focused where it was, instead of loading again.
 */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    val memory = LocalPageMemory.current
    var attempt by remember { mutableIntStateOf(0) }
    @Suppress("UNCHECKED_CAST")
    val kept = memory?.loads?.get(key) as? Load.Done<T>
    val state by produceState<Load<T>>(kept ?: Load.Loading, key, attempt) {
        val hit = if (attempt == 0) memory?.loads?.get(key) else null
        if (hit is Load.Done<*>) return@produceState
        value = Load.Loading
        value = try {
            Load.Done(block()).also { memory?.loads?.put(key, it) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Load.Failed }
    }
    return state to { attempt++ }
}

// ---- Focus ----

/**
 * The focus ring, drawn the cheap way: one solid stroke in [color], [TvFocus.RingGap] outside the
 * thing, only while [visible]. No shadow, no glow, no animation. Put it before (outside) any
 * `clip` so the ring isn't clipped away.
 */
fun Modifier.tvFocusRing(
    visible: Boolean,
    shape: Shape = TvShapes.Card,
    color: Color = TvColors.Focus,
): Modifier = if (!visible) this else drawWithCache {
    val stroke = TvFocus.RingWidth.toPx()
    val grow = TvFocus.RingGap.toPx() + stroke / 2
    val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
    onDrawWithContent {
        drawContent()
        translate(-grow, -grow) { drawOutline(outline, color, style = Stroke(stroke)) }
    }
}

/** One focusable thing's state: whether the remote's focus (or the phone's cursor) is on it. */
@Stable
class TvFocusState internal constructor(
    val requester: FocusRequester,
    internal val interactions: MutableInteractionSource,
    internal val key: String?,
    private val hover: State<Boolean>,
) {
    var focused by mutableStateOf(false)
        internal set

    /**
     * Focused, or under the phone remote's cursor: either way it takes the lighter fill. Only the
     * focus wears the ring ([focused]), so the cursor resting on one thing and the focus on
     * another never look like two focused things.
     */
    val lit: Boolean get() = focused || hover.value
}

/**
 * The focus state of one focusable thing, joined to the page's [PageMemory]:
 * - [focusKey]: unique on the page; the shell focuses this item again when the viewer comes back.
 * - [entryFocus]: the item to focus when the page opens and nothing is remembered (e.g. Play on a
 *   detail page). Without one, the shell focuses the page's first focusable item.
 * - [requester]: your own FocusRequester, if you also need to move the focus here yourself.
 * Pair it with [tvFocusTarget] on the element that should take the focus.
 */
@Composable
fun rememberTvFocus(focusKey: String? = null, entryFocus: Boolean = false, requester: FocusRequester? = null): TvFocusState {
    val interactions = remember { MutableInteractionSource() }
    val hover = interactions.collectIsHoveredAsState()
    val req = requester ?: remember { FocusRequester() }
    val state = remember(req, focusKey) { TvFocusState(req, interactions, focusKey, hover) }
    val memory = LocalPageMemory.current
    if (memory != null) {
        DisposableEffect(memory, focusKey, req, entryFocus) {
            if (focusKey != null) memory.requesters[focusKey] = req
            if (entryFocus) memory.entry = req
            if (memory.wantsFocus) memory.arrivals++
            onDispose {
                if (focusKey != null && memory.requesters[focusKey] === req) memory.requesters.remove(focusKey)
                if (memory.entry === req) memory.entry = null
            }
        }
    }
    return state
}

/**
 * Makes this element the focus target for [state]: the remote's arrows land on it, OK (or a
 * click from the phone's cursor) calls [onClick], a long press of OK calls [onLongClick].
 * A click from the cursor also moves the focus here, so the ring follows what was clicked
 * instead of staying on whatever the remote last reached.
 * It draws nothing; draw the fill from [TvFocusState.lit] and the ring from [TvFocusState.focused].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusTarget(
    state: TvFocusState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    enabled: Boolean = true,
    onFocused: () -> Unit = {},
): Modifier {
    val memory = LocalPageMemory.current
    val click: () -> Unit = { if (!state.focused) runCatching { state.requester.requestFocus() }; onClick() }
    val longClick: (() -> Unit)? = onLongClick?.let { long -> { if (!state.focused) runCatching { state.requester.requestFocus() }; long() } }
    return this
        .focusRequester(state.requester)
        .onFocusChanged { f ->
            if (f.isFocused != state.focused) {
                state.focused = f.isFocused
                if (f.isFocused) {
                    memory?.focusKey = state.key
                    onFocused()
                }
            }
        }
        .then(
            if (longClick != null) Modifier.combinedClickable(
                interactionSource = state.interactions, indication = null, enabled = enabled, role = role,
                onLongClick = longClick, onClick = click,
            )
            else Modifier.clickable(interactionSource = state.interactions, indication = null, enabled = enabled, role = role, onClick = click),
        )
}

/**
 * The base of every focusable piece: a [shape] filled with [container], or [focusedContainer]
 * while focused, with the accent ring ([ring]) and a grow to [scale] (1 = none; keep ≤ 1.05).
 * [content] gets whether it is lit, to change its own ink. See [rememberTvFocus] for [focusKey],
 * [entryFocus] and [focusRequester].
 */
@Composable
fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TvShapes.Card,
    scale: Float = 1f,
    container: Color = TvColors.Surface,
    focusedContainer: Color = TvColors.SurfaceHigh,
    ring: Boolean = true,
    role: Role = Role.Button,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val focus = rememberTvFocus(focusKey, entryFocus, focusRequester)
    val lit = focus.lit
    val grow = if (scale != 1f) animateFloatAsState(if (lit) scale.coerceAtMost(TvMotion.FocusScale) else 1f, tween(TvMotion.FocusMs), label = "grow") else null
    Box(
        modifier
            .then(if (grow != null) Modifier.zIndex(if (lit) 1f else 0f).graphicsLayer { val s = grow.value; scaleX = s; scaleY = s } else Modifier)
            .tvFocusRing(focus.focused && ring, shape)
            .clip(shape)
            .background(if (lit) focusedContainer else container, shape)
            .tvFocusTarget(focus, onClick, onLongClick, role, onFocused = onFocused),
    ) { content(lit) }
}

// ---- Artwork ----

/**
 * [url] asked for at about [px] pixels where the host lets us say (YouTube's, Apple's, Deezer's
 * image servers), else as it is: the app's shared [artworkAt], which downloaded songs' local
 * covers are matched through too.
 */
fun artUrl(url: String?, px: Int): String? = url.artworkAt(px)

/**
 * Cover art at a fixed size: give [modifier] a size, so nothing relayouts as the image comes in.
 * [px] is the size asked for: [TvArt.Row] for cards (the default), [TvArt.Small] in song rows,
 * [TvArt.Large] only for Now Playing and detail headers. No crossfade. A note glyph stands in
 * where there is no art.
 */
@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, shape: Shape = TvShapes.Card, px: Int = TvArt.Row) {
    Box(modifier.clip(shape).background(TvColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Rounded.MusicNote, null, tint = TvColors.TextFaint, modifier = Modifier.size(40.dp))
        else {
            val context = LocalContext.current
            val request = remember(url, px) {
                ImageRequest.Builder(context).data(artUrl(url, px)).size(px).crossfade(false).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

// ---- Cards ----

/** A card's shape: a square cover, a 16:9 one (videos), or a round face (artists). */
enum class TvCardKind(val width: Dp, val coverHeight: Dp, val shape: Shape) {
    Square(TvSizes.CardSquare, TvSizes.CardSquare, TvShapes.Card),
    Wide(TvSizes.CardWide, TvSizes.CardWide * 9 / 16, TvShapes.Card),
    Round(TvSizes.CardRound, TvSizes.CardRound, TvShapes.Round),
}

/**
 * A card for a shelf: the cover, then the title and a dim subtitle under it, one line each.
 * Focused, the cover takes the ring and grows a little; the text stays put.
 * [kind] picks square, wide (16:9) or round. [focusKey]: unique on the page, so the shell can
 * come back to it. [onLongClick]: a long press of OK, for a menu.
 */
@Composable
fun TvCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TvCardKind = TvCardKind.Square,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: () -> Unit = {},
) {
    val focus = rememberTvFocus(focusKey, entryFocus)
    val lit = focus.lit
    val grow by animateFloatAsState(if (lit) TvMotion.FocusScale else 1f, tween(TvMotion.FocusMs), label = "grow")
    val round = kind == TvCardKind.Round
    Column(
        modifier.width(kind.width).tvFocusTarget(focus, onClick, onLongClick, onFocused = onFocused),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Cover(
            imageUrl,
            Modifier.graphicsLayer { scaleX = grow; scaleY = grow }
                .tvFocusRing(focus.focused, kind.shape)
                .size(kind.width, kind.coverHeight),
            kind.shape,
        )
        Spacer(Modifier.height(TvSpace.M))
        Text(
            title, style = TvType.ItemTitle, color = TvColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * A wide, short tile of solid colour with a name on it, for things without art: moods, genres,
 * categories. [color] defaults to one picked from the name ([TvColors.tileColor]).
 */
@Composable
fun TvTextTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TvColors.tileColor(label),
    focusKey: String? = null,
) {
    TvSurface(
        onClick = onClick, shape = TvShapes.Card, scale = TvMotion.FocusScale,
        container = color, focusedContainer = color, focusKey = focusKey,
        modifier = modifier.size(TvSizes.TileWidth, TvSizes.TileHeight),
    ) { _ ->
        Text(
            label, style = TvType.ItemTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(TvSpace.L),
        )
    }
}

/** The card for a song: its cover, title and artist. */
@Composable
fun SongCard(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier, focusKey: String? = null) =
    TvCard(song.title, song.artist, song.thumbnailUrl, onClick, modifier, focusKey = focusKey)

/** The card for a shelf item: round for an artist, wide for a video, square for the rest. */
@Composable
fun ShelfCard(item: ShelfItem, onClick: () -> Unit, modifier: Modifier = Modifier, focusKey: String? = null) =
    TvCard(
        item.title, item.subtitle, item.thumbnailUrl, onClick, modifier,
        kind = when {
            item.browseId?.startsWith("UC") == true -> TvCardKind.Round
            item.video -> TvCardKind.Wide
            else -> TvCardKind.Square
        },
        focusKey = focusKey,
    )

// ---- Buttons ----

/**
 * A pill button with a label and maybe an icon: Play, Shuffle, Sign in, Try again.
 * [primary] for the one that matters on a screen (accent fill); others are quiet.
 */
@Composable
fun TvPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    entryFocus: Boolean = false,
) {
    TvSurface(
        onClick = onClick,
        shape = TvShapes.Pill,
        container = if (primary) TvColors.Accent else TvColors.SurfaceHigh,
        focusedContainer = if (primary) TvColors.AccentBright else TvColors.SurfaceTop,
        focusRequester = focusRequester, focusKey = focusKey, entryFocus = entryFocus,
        modifier = modifier.height(TvSizes.Button),
    ) { _ ->
        val ink = if (primary) TvColors.OnAccent else TvColors.Text
        Row(
            Modifier.align(Alignment.Center).padding(start = if (icon != null) 22.dp else 28.dp, end = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpace.S),
        ) {
            if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(TvSizes.Icon))
            Text(label, style = TvType.Label, color = ink, maxLines = 1)
        }
    }
}

/**
 * A round button with an icon alone; [description] is read out and names it for tests.
 * [primary] for the main one (Play/Pause): accent fill. [active] when what it stands for is on
 * (shuffle, liked): the icon takes [activeTint].
 */
@Composable
fun TvIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    active: Boolean = false,
    size: Dp = if (primary) 72.dp else TvSizes.IconButton,
    activeTint: Color = TvColors.Accent,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    entryFocus: Boolean = false,
) {
    TvSurface(
        onClick = onClick,
        shape = TvShapes.Round,
        container = if (primary) TvColors.Accent else TvColors.SurfaceHigh,
        focusedContainer = if (primary) TvColors.AccentBright else TvColors.SurfaceTop,
        focusRequester = focusRequester, focusKey = focusKey, entryFocus = entryFocus,
        modifier = modifier.size(size),
    ) { _ ->
        Icon(
            icon, description,
            tint = when {
                primary -> TvColors.OnAccent
                active -> activeTint
                else -> TvColors.Text
            },
            modifier = Modifier.align(Alignment.Center).size(size * 0.5f),
        )
    }
}

/**
 * A full-width row to press: a setting, a menu entry, a playlist in a list. [title], a dim
 * [subtitle] under it, an optional [icon] at the start and a [value] at the end in the accent
 * (a setting's current choice). Rows sit [TvSafe.Horizontal] in from the edges by default.
 */
@Composable
fun TvRowButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    TvSurface(
        onClick = onClick, shape = TvShapes.Row,
        container = TvColors.Surface, focusedContainer = TvColors.SurfaceHigh,
        focusKey = focusKey, entryFocus = entryFocus, focusRequester = focusRequester,
        modifier = modifier.fillMaxWidth(),
    ) { _ ->
        Row(Modifier.padding(horizontal = TvSpace.L, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = TvColors.TextDim, modifier = Modifier.size(TvSizes.Icon))
                Spacer(Modifier.width(TvSpace.L))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = TvType.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = TvType.Meta, color = TvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) Text(value, style = TvType.Label, color = TvColors.Accent, maxLines = 1, modifier = Modifier.padding(start = TvSpace.L))
        }
    }
}

// ---- Song rows ----

/**
 * One song in a list: [index] (or the now-playing mark), the cover, title and artist, and the
 * length at the end. [playing] marks the song that is playing now: a still equalizer glyph in
 * place of the number (or, without a number, a small badge on the cover's corner, the cover
 * still showing) and the title in the accent. [trailing] is a short tag before the length ("E",
 * "Video"). [showCover] false for an album's own tracks, which all share one cover. The row fills
 * the width: in a [TvPage], pass `Modifier.safeSides()`.
 *
 * The layout is the same in every state: playing, focused or both only change colours and the
 * mark, never where the cover and the words sit. Whether there is a number column depends on
 * [index] alone, so give every row of a list an index, or none.
 */
@Composable
fun TvSongRow(
    title: String,
    artist: String,
    duration: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailing: String? = null,
    showCover: Boolean = true,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    TvSurface(
        onClick = onClick, shape = TvShapes.Row,
        container = Color.Transparent, focusedContainer = TvColors.SurfaceHigh,
        focusKey = focusKey, entryFocus = entryFocus, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
    ) { _ ->
        // Every slot below has a fixed size that depends only on index and showCover, so the
        // cover and the words stay put as the row is focused or starts playing.
        Row(Modifier.padding(horizontal = TvSpace.M, vertical = TvSpace.S), verticalAlignment = Alignment.CenterVertically) {
            if (index != null) Box(Modifier.size(RowNumberWidth, TvSizes.RowCover), contentAlignment = Alignment.CenterStart) {
                if (playing) Icon(Icons.Rounded.GraphicEq, "Now Playing", tint = TvColors.Accent, modifier = Modifier.size(TvSizes.Icon))
                else Text(index.toString(), style = TvType.Meta, color = TvColors.TextDim, maxLines = 1)
            }
            if (showCover) {
                Box(Modifier.size(TvSizes.RowCover)) {
                    Cover(imageUrl, Modifier.size(TvSizes.RowCover), TvShapes.Small, px = TvArt.Small)
                    // No number to swap for the mark: a small badge on the corner, the cover still in view.
                    if (playing && index == null) Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).size(RowBadge).background(TvColors.Accent, TvShapes.Round),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.GraphicEq, "Now Playing", tint = TvColors.OnAccent, modifier = Modifier.size(RowBadge - 6.dp)) }
                }
                Spacer(Modifier.width(TvSpace.L))
            } else if (index == null) Spacer(Modifier.width(TvSpace.S))
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = TvType.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) TvColors.Accent else TvColors.Text,
                )
                Text(artist, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, style = TvType.Meta, color = TvColors.TextFaint, maxLines = 1, modifier = Modifier.padding(start = TvSpace.L))
            if (duration != null) Text(duration, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, modifier = Modifier.padding(start = TvSpace.L))
        }
    }
}

/** A song row's number column, wide enough for "999" or the now-playing mark. */
private val RowNumberWidth = 44.dp
/** The now-playing badge on a song row's cover, when the row has no number to swap for it. */
private val RowBadge = 22.dp

/** [TvSongRow] for a [Song]. */
@Composable
fun TvSongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    focusKey: String? = null,
    entryFocus: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) = TvSongRow(
    song.title, song.artist, song.durationText, song.thumbnailUrl, onClick, modifier,
    index = index, playing = playing, focusKey = focusKey, entryFocus = entryFocus, onLongClick = onLongClick,
)

// ---- Shelves and pages ----

/** Keeps a focused item at least [margin] in from both ends of a row, so the TV's overscan can't crop it. */
private class EdgeSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lead = margin
        val trail = containerSize - margin
        return when {
            size > trail - lead -> offset - lead
            offset < lead -> offset - lead
            offset + size > trail -> offset + size - trail
            else -> 0f
        }
    }
}

/**
 * How a [TvPage] scrolls as the focus moves down it: the focused item's top is kept between two
 * heights, as fractions of the page's height from its top. Within the band nothing moves; outside
 * it the page scrolls just far enough to bring the item to the band's nearer edge.
 */
object TvScroll {
    /** Rows of shelves: the focused row always comes to the same height, near the top. */
    val Shelves = 0.16f..0.16f
    /** A list under a header (an album, Settings): the header stays until the focus is halfway down. */
    val List = 0.12f..0.5f
}

/**
 * Compose asks again on every frame of a scroll with the item's new offset, so the answer must
 * name a fixed target (the band's nearer edge), not "stop once inside", or the scroll stops short.
 */
private class BandSpec(private val band: ClosedFloatingPointRange<Float>) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lo = containerSize * band.start
        val hi = containerSize * band.endInclusive
        return when {
            offset < lo -> offset - lo
            offset > hi -> offset - hi
            else -> 0f
        }
    }
}

/** Pads a page item in from the screen's sides by [TvSafe.Horizontal]. Shelves and rows already are. */
fun Modifier.safeSides(): Modifier = padding(horizontal = TvSafe.Horizontal)

/**
 * A titled row of cards scrolling sideways, its first card in line with the page's text. Moving
 * back into a row returns to the card last focused in it. Give items stable keys, and each card a
 * `focusKey` unique on the page (e.g. "$shelfKey/$itemKey").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvShelf(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(
            title, style = TvType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.safeSides(),
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, style = TvType.Meta, color = TvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.safeSides(),
        )
        val density = LocalDensity.current
        val spec = remember(density) { EdgeSpec(with(density) { TvSafe.Horizontal.toPx() }) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(
                state = state,
                // Room above and below the cards for the ring and the grow, which the row would clip.
                contentPadding = PaddingValues(horizontal = TvSafe.Horizontal, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(TvSpace.L),
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                content = content,
            )
        }
    }
}

/** A page's title and a dim line under it, in from the sides. */
@Composable
fun TvPageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.safeSides()) {
        Text(title, style = TvType.Headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = TvType.Body, color = TvColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A page under the shell's bar: one vertical list ([content]), with an optional [title] header.
 * Items are [spacing] apart; give each a stable key. The list keeps its own sides at the screen
 * edge so shelves can scroll out to it: pad your own items with [safeSides] (shelves and states
 * already are; song rows and row buttons are not). [scroll] is where the focused item is kept:
 * [TvScroll.Shelves] for rows of cards, [TvScroll.List] for a list under a header. Its scroll
 * position comes back with the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvPage(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    spacing: Dp = TvSpace.XL,
    scroll: ClosedFloatingPointRange<Float> = TvScroll.Shelves,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val spec = remember(scroll) { BandSpec(scroll) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyColumn(
            state = state,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = TvSpace.S, bottom = TvSafe.Vertical + TvSpace.XL),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (title != null) item(key = "tv:header") { TvPageHeader(title, subtitle) }
            content()
        }
    }
}

// ---- States ----

/** A quiet line of text in a page: a hint, a short note under a list. */
@Composable
fun TvNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TvType.Body, color = TvColors.TextDim, modifier = modifier.safeSides().padding(vertical = TvSpace.M))
}

/**
 * What a page shows when there is nothing in it: an [icon], a [title], a dim [message] and, if
 * given, one button ([actionLabel], [onAction]) that takes the focus.
 */
@Composable
fun TvEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = Icons.Rounded.MusicNote,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().safeSides().padding(vertical = TvSpace.XL), verticalAlignment = Alignment.Top) {
        if (icon != null) {
            Box(Modifier.size(72.dp).background(TvColors.Surface, TvShapes.Round), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = TvColors.TextDim, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(TvSpace.L))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
            Text(title, style = TvType.Title)
            if (!message.isNullOrBlank()) Text(message, style = TvType.Body, color = TvColors.TextDim)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(TvSpace.S))
                TvPillButton(actionLabel, onAction, icon = actionIcon, primary = true, entryFocus = true)
            }
        }
    }
}

/**
 * What a page shows when its data didn't come: [message] and Try again, which takes the focus.
 * The default message fits anything loaded from YouTube Music.
 */
@Composable
fun TvErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "Couldn't reach YouTube Music just now. Check the connection and try again.",
) = TvEmptyState(
    title = "No connection", message = message, icon = Icons.Rounded.CloudOff,
    actionLabel = "Try again", actionIcon = Icons.Rounded.Refresh, onAction = onRetry, modifier = modifier,
)

/** The kinds of placeholder [TvLoadingState] draws, shaped like what is coming. */
enum class TvLoadingKind { Shelf, Rows }

/**
 * What a page shows while its data comes: still grey shapes where the shelf or the song rows will
 * be, and [label] for screen readers. Nothing moves (no spinner, no shimmer): a loop costs a weak
 * TV's GPU every frame.
 */
@Composable
fun TvLoadingState(modifier: Modifier = Modifier, kind: TvLoadingKind = TvLoadingKind.Shelf, label: String = "Loading…") {
    Column(modifier.fillMaxWidth().safeSides(), verticalArrangement = Arrangement.spacedBy(TvSpace.M)) {
        Text(label, style = TvType.Meta, color = TvColors.TextFaint)
        when (kind) {
            TvLoadingKind.Shelf -> Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(TvSpace.L)) {
                repeat(6) {
                    Column(verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        Box(Modifier.size(TvSizes.CardSquare).background(TvColors.Surface, TvShapes.Card))
                        Box(Modifier.size(140.dp, 18.dp).background(TvColors.Surface, TvShapes.Small))
                        Box(Modifier.size(96.dp, 16.dp).background(TvColors.Surface, TvShapes.Small))
                    }
                }
            }
            TvLoadingKind.Rows -> repeat(5) {
                Row(Modifier.padding(horizontal = TvSpace.M), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(TvSizes.RowCover).background(TvColors.Surface, TvShapes.Small))
                    Spacer(Modifier.width(TvSpace.L))
                    Column(verticalArrangement = Arrangement.spacedBy(TvSpace.S)) {
                        Box(Modifier.size(320.dp, 18.dp).background(TvColors.Surface, TvShapes.Small))
                        Box(Modifier.size(180.dp, 16.dp).background(TvColors.Surface, TvShapes.Small))
                    }
                }
            }
        }
    }
}
